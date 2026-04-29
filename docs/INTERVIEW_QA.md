# Interview Q&A — 기술 결정 정리

이 문서는 콘서트 티켓 예매 시스템(`ticket-booking-system`) 프로젝트의 핵심 기술 결정을 **면접 답변 가이드** 형식으로 정리한 것입니다. 각 항목은 "왜 이 선택을 했는가"와 면접에서 받을 만한 후속 질문에 대한 답변 포인트를 담고 있습니다.

> 본 프로젝트는 단일 노드 환경에서의 **동시성·일관성 보장**과 **AI 페어 프로그래밍 워크플로우 설계**를 핵심 학습 목표로 진행했습니다. 자세한 결정 배경은 [`docs/ADR.md`](./ADR.md), 측정 결과는 [`docs/BENCHMARK.md`](./BENCHMARK.md)를 참고하세요.

---

## 목차

1. [동시성·일관성 보장](#1-동시성·일관성-보장)
2. [도메인 상태머신·멱등성](#2-도메인-상태머신·멱등성)
3. [인증·보안](#3-인증·보안)
4. [대기열](#4-대기열)
5. [결제·아키텍처 패턴](#5-결제·아키텍처-패턴)
6. [운영·관측](#6-운영·관측)
7. [인프라·테스트 전략](#7-인프라·테스트-전략)
8. [부하 테스트 인사이트](#8-부하-테스트-인사이트)
9. [AI 협업 워크플로우와 검증 책임](#9-ai-협업-워크플로우와-검증-책임)

---

## 1. 동시성·일관성 보장

### 1-1. 좌석 선점에 DB 락이 아닌 Redis `SET NX PX`를 쓴 이유

**Q. 왜 DB 비관락이나 낙관락이 아니라 Redis인가?**
- DB 비관락(`SELECT FOR UPDATE`)은 트랜잭션 동안 row 락을 점유 → 결제 10분 동안 connection이 묶여 **pool 고갈 위험**.
- 낙관락(`@Version`)은 충돌 시 retry 로직 필요 → N명 경합 시 N-1명이 retry 부하 증폭.
- Redis `SET NX PX`는 **단일 원자 연산 O(1)**으로 경합 종료. DB 자원 소비 0.

**Q. `SETNX` 후 `EXPIRE` 두 단계로 나누면 안 되나?**
- 두 단계 사이에 클라이언트가 죽으면 TTL 없는 영구 키가 남음(유령 선점).
- `SET key value NX PX 600000`은 Redis 2.6.12+에서 한 명령으로 원자적.

**Q. Redis 단일 장애점은?**
- 인지하고 있다. MVP는 단일 노드. 확장 경로는 Redis Sentinel·Cluster, 더 강한 보장은 Redisson `RLock` + Redlock.
- 트레이드오프를 ADR-002에 명시.

### 1-2. Redis ↔ DB 일관성 — 보상 트랜잭션

**Q. Redis SET 성공 후 DB insert가 실패하면?**
- `TransactionSynchronizationManager.registerSynchronization`의 `afterCompletion` 콜백에서 rollback 감지 → Redis 키 DEL + 카운터 DECR.
- 보상이 또 실패하면? **TTL 10분이 안전망**. ERROR 로그만 남기고 자동 해제 후 재시도 가능.

**Q. 왜 Saga 같은 무거운 패턴을 쓰지 않았나?**
- 분산 시스템이 아닌 단일 노드 구성이라 트랜잭션 동기화 콜백으로 충분. Saga는 over-engineering.

### 1-3. 1000명 동시 좌석 경합 → 1명만 성공이 *성공의 정의*

**Q. 부하 테스트에서 99% 실패율이 나왔는데 가용성 문제 아닌가?**
- 그 수치는 "좌석 1개에 1000명 의도적 경합" 시뮬레이션. 정답이 **1 성공·999 실패** 인 것.
- 99의 "실패"는 시스템 에러가 아니라 `409 SEAT_ALREADY_HELD` — 즉시 거절되어 다른 좌석으로 재시도 가능.
- 만약 2 성공이 나오면 **데이터 무결성 사고**(중복 예매).

**Q. 가용성 검증은 어디서?**
- `scenario-distributed.js`의 분산 좌석 시나리오. 동시성은 correctness, 부하는 throughput·latency — 차원이 다르다.

### 1-4. 1인당 좌석 4매 한도

**Q. 왜 4매? 어떻게 구현?**
- 토스·인터파크 등 한국 티켓팅 도메인의 사실상 표준. 매크로·스캘퍼 방어.
- 카운터 키 `count:user:{userId}:{showId}` (TTL 10분, 좌석 선점 TTL과 동일). 선점 시 INCR, 만료/취소 시 DECR.
- INCR 자체가 원자라 동시성 안전.

### 1-5. PENDING 만료 처리 — 이중 안전망

**Q. TTL 10분 후 Redis 키는 사라지는데 DB의 PENDING은?**
- (a) **결제 시점 검증**: `pay()` 진입 시 Redis 키 부재 확인 → 자동 `cancel()` + 410 `EXPIRED_RESERVATION`.
- (b) **`@Scheduled` 워커**: 5분마다 PENDING + (created_at + 10분 < now) 일괄 CANCELLED + Redis 정리. `FOR UPDATE SKIP LOCKED`로 동시 워커 안전.

**Q. 왜 둘 다 두나?**
- (a)만: 사용자가 결제 안 하고 잠수 → DB에 영구 PENDING 잔존 → 통계 깨짐.
- (b)만: 만료 직후~워커 실행 사이 5분간 stale 상태 가능.
- 둘이 보완 — (a)는 즉시성, (b)는 통계 정합성.

### 1-6. 동시성 테스트 작성

**Q. 어떻게 검증했나?**
- `ExecutorService` + `CountDownLatch` 패턴. 100~1000 스레드가 같은 좌석 선점 → `latch.countDown()` 후 일제히 출발.
- 검증 항목: 성공 카운트 = 1, 실패 카운트 = N-1, DB Reservation row = 1, Redis seat 키 = 1.
- Testcontainers로 실제 Postgres·Redis를 띄워 production과 동일한 동작 보장.

---

## 2. 도메인 상태머신·멱등성

### 2-1. 예약 상태를 3-state로 단순화한 이유

**Q. 상태가 3개뿐인데 "상태머신"이라 부를 만한가?**
- 의도적 단순화. mock PG 환경에서 "결제 진행 중"(`PAYMENT_PROCESSING`) 상태가 instant라 의미 없음.
- 상태가 늘면 전이 규칙·테스트 케이스가 ~2배씩 증가. mock 환경에서 가짜 복잡도.

**Q. 실제 PG 연동 시 확장은?**
- `PAYMENT_PROCESSING` 상태 추가. `PENDING → PAYMENT_PROCESSING → PAID/CANCELLED`.
- 보상 워커: 일정 시간 지속 시 PG 상태 조회 API → 동기화. 현재 enum + 메서드 기반이라 추가 비용 작음.

**Q. 왜 상태 전이를 서비스가 아닌 엔티티 메서드로?**
- 서비스에 분기를 두면 같은 규칙이 여러 곳에 흩뿌려져 깨지기 쉬움.
- `Reservation.pay()` 안에 "PENDING이 아니면 throw"를 두면 규칙이 한 곳에 모임. Anemic 도메인 안티패턴 회피.

**Q. Spring Statemachine 같은 라이브러리는?**
- 상태가 3개뿐이라 학습·설정 비용이 더 큼. enum + 메서드로 도메인 의도 충분히 표현.
- 상태가 5~6개 이상으로 늘면 라이브러리 도입 검토.

### 2-2. 결제 멱등성과 낙관락

**Q. `POST /pay`가 두 번 호출되면?**
- PAID 상태에서 `pay()` 재호출 시 예외 없이 기존 응답 반환 (200). 멱등.
- 클라이언트 네트워크 재시도가 안전. 외부 PG의 멱등성 키 패턴과 동일 사상.

**Q. 동시 결제 시도는 `@Transactional`만으로 충분한가?**
- 부족함. `@Transactional`은 격리만 보장하지 row contention을 막진 않음.
- `Reservation`에 `@Version` 낙관락 → 동시 update 시 `OptimisticLockException` → 409 `RESERVATION_CONFLICT`.
- 비관락도 가능하나 락 보유 시간이 길어져 throughput 저하.

**Q. 왜 `Idempotency-Key` 헤더를 안 썼나?**
- MVP 범위에서 `reservation id`가 자연스러운 멱등성 키 역할. 추가 헤더는 over-engineering.
- 외부 PG 연동 시 PG의 멱등성 키와 결합해 도입 검토.

---

## 3. 인증·보안

### 3-1. JWT Access + Refresh + Rotation

**Q. Access만 쓰지 않은 이유?**
- Access 수명을 짧게(15분) 가져갈 수 있어 토큰 탈취 피해 최소화.
- 클라이언트가 refresh로 자동 재발급해 UX는 매끄럽게 유지.

**Q. Stateless인 JWT의 장점을 Redis 도입으로 깎아먹는 거 아닌가?**
- Access는 Stateless 유지 (서버 상태 조회 없이 검증).
- Refresh만 Stateful (revoke 가능성 확보). 트레이드오프: stateless 일부 포기 = 즉시 revoke 가능성 획득.

### 3-2. 1 user = 1 refresh 정책

**Q. 왜 jti 안 쓰고 단일 키 `refresh:{userId}`?**
- KEYS·SCAN 회피 + 모든 작업 O(1). logout = `DEL refresh:{userId}` 한 번.
- 멀티 디바이스 미지원이 단점. MVP 단순화 결정.

**Q. KEYS는 왜 안 쓰나?**
- O(N) blocking 명령. Redis는 single-thread라 KEYS 실행 동안 다른 명령 모두 대기 → 프로덕션 장애 원인.
- 대안 SCAN(non-blocking)도 1 user = 1 refresh 단순화로 불필요해짐.

**Q. 멀티 디바이스 확장은?**
- 키를 `refresh:{userId}:{deviceId}` 또는 `refresh:{userId}:{jti}` 로 변경.
- logout-all은 SCAN prefix 매칭 또는 별도 인덱스 자료구조(`refresh_index:{userId}` Set).

### 3-3. Refresh Rotation의 Reuse Detection

**Q. 어떻게 동작?**
- 정상 흐름: refresh 사용 → Redis에서 즉시 DELETE → 새 refresh SET.
- 탈취 시나리오: 공격자가 가로챈 refresh로 2번째 호출 → Redis 키 부재 → reuse 의심 → 해당 유저 모든 refresh DEL → 강제 재로그인.
- 즉, "valid JWT인데 Redis에 없음" = 탈취 의심 신호.

---

## 4. 대기열

### 4-1. Kafka·RabbitMQ가 아닌 Redis Sorted Set

**Q. 왜 메시지 큐가 아닌가?**
- 요구사항: FIFO + **순번 조회** + **원자적 pop**.
- Kafka는 순번 조회 없음(offset만), RabbitMQ도 마찬가지.
- Sorted Set은 ZADD/ZRANK/ZPOPMIN로 셋 다 O(log N).

**Q. score를 timestamp로 한 이유?**
- FIFO 보장 + tie-breaker 자연스러움.
- ZADD NX와 함께 쓰면 같은 user 두 번 호출해도 첫 진입 timestamp 유지.

**Q. 워커가 ZPOPMIN 후 admit Set에 SADD 직전에 죽으면?**
- 사용자 유실. MVP 정책: 사용자가 다시 enter 가능 (큐 자체가 ephemeral).
- 정합성 강화 옵션: Lua 스크립트로 `ZPOPMIN + SADD` 원자화 (다음 마일스톤).

### 4-2. 큐 활성화는 Concert flag 기반

**Q. 모든 공연에 큐 강제 안 한 이유?**
- 인기 없는 공연은 큐 자체가 오버헤드. `Concert.queueEnabled` flag로 운영자가 결정.
- 실서비스에서도 "사전 예매 오픈일에만 활성화" 같은 패턴 일반적.

**Q. 임계치 자동 활성화는?**
- MVP 제외. RPS 측정 기반 자동 on은 부수 복잡도 큼. 부하 테스트로 임계치 확인 후 별도 마일스톤.

---

## 5. 결제·아키텍처 패턴

### 5-1. Mock 결제 — Deterministic Mode

**Q. 왜 외부 PG 안 붙였나?**
- MVP 범위. 실제 PG는 테스트 키 발급·웹훅 인프라가 추가로 필요.
- 동시성·일관성 시연이 본 프로젝트의 목표라 PG 통합은 부수적.

**Q. mock인데 90/10 랜덤? 테스트 reproducibility는?**
- `X-Mock-Pay-Result: success|fail` 헤더로 결과 강제 가능 (deterministic mode).
- 부하 테스트에서는 랜덤 모드로 실패 시나리오 자연스럽게 발생.

### 5-2. Port-Adapter 패턴 (Hexagonal Architecture)

**Q. mock 결제인데 왜 굳이 인터페이스를 분리했나?**
- 외부 PG는 본질적으로 교체 가능성이 큰 영역(토스·카카오페이·네이버페이·포트원 등).
- `PaymentGateway` interface(port) + `MockPaymentGatewayAdapter`(adapter) 분리 → 도메인은 외부 시스템을 모름. 새 PG 도입 시 어댑터만 추가.
- 테스트에서 Mock 어댑터로 외부 호출 없이 검증 가능.

**Q. 인터페이스 남발 아닌가?**
- 맞다. **외부 경계에만** Port를 둠. 외부 시스템(PG, SMS, Email) ✓ / 내부 단순 컴포넌트 ✗.
- "이론적으로 교체될 수 있다"는 이유로 모든 곳에 인터페이스 박지 않음.

**Q. 면접 답변 패턴**
> "PG는 외부 시스템이라 변동 가능성을 인지하고 Hexagonal로 격리했고, 도메인 핵심은 PG 디테일을 모르게 했습니다. 반대로 내부 단순 컴포넌트는 직접 구현으로 두어 인터페이스 남발은 피했습니다."

---

## 6. 운영·관측

### 6-1. 시간·타임존 — UTC + `Instant` + Clock 주입

**Q. 왜 `LocalDateTime` 안 쓰고 `Instant`?**
- `LocalDateTime`은 타임존 없는 wall-clock. 서머타임·DST 시 모호한 시각 발생.
- `Instant`는 epoch millis 기반 절대 시각. 타임존 무관.
- 좌석 선점 만료 등 시간 비교 로직에서 타임존 혼재 버그 차단.

**Q. 응답이 UTC면 사용자가 KST로 못 보는데?**
- 클라이언트가 변환. ISO-8601 `Z` 접미사라 모든 표준 라이브러리가 깔끔히 파싱.
- 백엔드는 단일 진실(UTC) 유지. 표시는 표현 계층 책임.

**Q. `Clock` 주입 강제 이유?**
- 시간 의존 테스트(예: 10분 후 PENDING 자동 취소)가 wall-clock에 묶이면 flaky 또는 sleep으로 느려짐.
- `Clock.fixed(...)`로 결정적 검증. production은 `Clock.systemUTC()` 자동 주입.

### 6-2. 로깅 정책 — PII 절대 금지 + MDC requestId

**Q. PII 로깅 금지 위반 시 어떻게?**
- 비밀번호·해시·JWT 토큰 로깅 = 로그 노출 시 즉시 보안 사고. log shipper로 ELK·CloudWatch 등에 흩어지면 회수 불가.
- 사용자 식별은 `userId`(숫자) 단일 컨벤션.

**Q. requestId는 어떻게 분산 추적에 쓰나?**
- 모든 응답에 `X-Request-Id` 헤더. 클라이언트가 받아 다음 요청·에러 리포트에 포함.
- ELK·Loki에서 requestId로 필터링 → 단일 요청의 모든 로그 라인 모음.

---

## 7. 인프라·테스트 전략

### 7-1. Spring Boot 3 + Java 21 + Gradle Groovy

**Q. 왜 Java 21?**
- LTS. virtual thread로 I/O 바운드 워크로드의 부하 스토리 확장 여지.
- Record, sealed, pattern matching 등 표현력 향상.

**Q. Gradle Kotlin DSL이 대세인데 왜 Groovy?**
- 한국 회사 다수가 아직 Groovy DSL 기반이라 친숙도 우세. IDE 지원·문서 양은 둘 다 충분.

### 7-2. 테스트는 Testcontainers (H2 금지)

**Q. H2가 빠르고 가벼운데 왜 안 쓰나?**
- H2는 SQL 방언이 Postgres와 다름 (`SELECT FOR UPDATE NOWAIT`, `JSONB`, `TIMESTAMPTZ` 등).
- 동시성 테스트(좌석 선점 100명 경합)는 실제 Postgres MVCC 동작 필요. H2는 보장 안 함.
- 부하 테스트 결과 신뢰도가 H2 검증으론 안 나옴.

**Q. Testcontainers가 느리지 않나?**
- `@Container static` + reuse mode로 컨테이너 재사용. 첫 1회만 느리고 그 후는 빠름.
- CI에서도 동일 인프라로 검증 가능.

---

## 8. 부하 테스트 인사이트

### 8-1. Virtual Thread는 켜기만 하면 효과 없거나 악화될 수 있다

**Q. VT를 활성화한 후 p95 latency가 악화됐다고? 왜?**
- 단순 활성화 후 좌석 경합 시나리오 p95가 578ms → 1.64s로 **+184% 악화**.
- 분석 결과 Tomcat platform thread의 암묵적 throttling(200 max)이 사라지면서 1000개 동시 요청이 HikariCP 기본 pool 10개에 모두 몰림.
- Pool 100으로 조정하니 p95 -58% 회복.

**Q. 그래서 결론은?**
- VT 단독 도입은 의미 없음. **다운스트림 자원(DB pool, Redis 연결)과 함께 튜닝되어야** 효과 있음.
- 짧은 트랜잭션엔 VT의 절대 이점 작음. **I/O 바운드 + 긴 대기** 시나리오에 적합.
- 자세한 측정은 [`docs/BENCHMARK.md`](./BENCHMARK.md) 참조.

### 8-2. k6 `http_req_failed` metric의 도메인 의미 결여

**Q. 부하 테스트에서 http_req_failed가 33%로 나왔다. 시스템 장애인가?**
- 아니다. k6의 `http_req_failed`는 **2xx 외 응답을 fail로 분류** (HTTP status 기반, 도메인 의미 없음).
- 좌석 경합에서 999 reserve가 `409 SEAT_ALREADY_HELD`를 받는데 이는 **의도된 정상 동작**이지만 4xx라 fail로 카운트.
- 진짜 시스템 장애는 0.1% 수준 (환경적 노이즈).

**Q. 어떻게 측정 품질을 보강하나?**
- `http.setResponseCallback(http.expectedStatuses({min:200,max:299}, 409))`로 409도 expected 등록.
- 또는 tag별 분리(`http_req_failed{name:reserve}`)로 시나리오 의도에 맞게 평가.

**Q. 일반화하면?**
- 부하 테스트 도구의 metric은 그대로 신뢰하지 말고 **도메인 정의에 맞게 해석/재정의**해야 한다.

### 8-3. Setup() 패턴으로 측정 노이즈 분리

**Q. 1000 VU가 동시에 signup→login하니 BCrypt CPU 포화로 21%만 성공했다. 어떻게 해결?**
- k6 `setup()` 단계에서 사용자 1000명을 순차 미리 생성 → iteration은 좌석 reserve만 동시 실행.
- BCrypt 부하를 측정 대상(좌석 경합)에서 분리.

**Q. setup이 ~3분 걸리는데 비효율 아닌가?**
- 측정 시간이 아닌 **측정 준비 시간**이라 일회성 비용으로 수용.
- 더 줄이려면 사용자 사전 batch script + 토큰 파일 주입 방식 가능하나 복잡도 증가.

---

## 9. AI 협업 워크플로우와 검증 책임

### 9-1. AI 출력을 그대로 신뢰하지 않는다 — 도메인 제약 누락 사례

**Q. 자동 생성된 코드를 그대로 믿고 진행하는가?**
- 안 한다. 부하 테스트의 `loadtest/lib/auth.js`에서 generator가 username을 `smoke1_0_12345` 형태로 만들었는데 **백엔드 validation `^[가-힣A-Za-z0-9]+$`가 underscore를 거부** → 모든 signup 400 → 후속 login 401 폭주.
- smoke test 1회 실행 + 서버 로그 1줄로 즉시 원인 추적 → 수정 커밋.

**Q. 왜 이런 일이 발생했나?**
- step 명세에 "auth.js의 randomUsername이 백엔드 username 정책에 맞아야 한다"는 제약을 명시하지 않음.
- 자동화 에이전트는 도메인 제약을 알지 못한 채 일반적 timestamp 패턴(`_` 구분자)을 채택.
- 본질적으로 **사람이 step 명세에 도메인 제약을 빠뜨린 인적 오류**. 자동화의 한계가 아닌 명세 불완전성.

**Q. 면접 답변 패턴**
> "자동화된 코드 생성을 도구로 활용하지만 결과물은 항상 검증합니다. 부하 테스트 단계에서 username regex 미일치 버그를 발견하고 즉시 수정한 사례가 있습니다. 자동화의 효율성과 검증 책임의 균형을 인지하고 있습니다."

**Q. 다른 비슷한 사례를 미리 막을 수 있나?**
- step 명세에 "백엔드 도메인의 validation/regex/제약을 우회하는 테스트 데이터 사용 금지" 같은 횡단 가이드 추가 가능.
- 또는 helper 작성 step에서 백엔드 endpoint를 1회 호출해 응답 status code 검증을 AC로 추가 (smoke 패턴).

### 9-2. 사람과 AI의 역할 분리

본 프로젝트의 워크플로우에서 **사람의 영역**으로 명시한 항목들:

- **PRD·ADR·ARCHITECTURE 문서** — 무엇을 만들지, 왜 그렇게 만들지
- **9개의 ADR 결정** — Spring Boot 3.2 / Redis SET NX PX / 3-state 상태머신 / 1 user = 1 refresh / Sorted Set 대기열 / mock PG / Testcontainers / UTC + Instant / 로깅 정책
- **11개의 CRITICAL 규칙** — 상태 전이 메서드 강제 / 시간은 Clock 주입 / PII 로깅 금지 / `@Valid` 강제 등
- **트레이드오프 의사결정** — RESERVED 상태 제거(3-state 단순화) / 멀티 디바이스 미지원 등
- **검증·회고** — 측정 결과 분석, 자동화 출력 검증, 인적 오류 회고

**AI의 영역**: 명세된 코드 작성, 테스트 보일러플레이트 생성, 반복적인 패턴 적용.

이 분리가 명확할수록 AI의 효율과 사람의 판단이 모두 살아난다는 것을 본 프로젝트로 학습했습니다.

---

## 부록: 결정 요약 매트릭스

| 영역 | 핵심 결정 | 트레이드오프 |
|---|---|---|
| 좌석 선점 | Redis SET NX PX 원자 연산 | Redis 단일 장애점 |
| 상태머신 | 3-state (PENDING/PAID/CANCELLED) | 실제 PG 도입 시 PAYMENT_PROCESSING 추가 필요 |
| 결제 멱등성 | PAID 재호출 시 200 + 기존 응답 | 명시적 idempotency key 미사용 |
| 동시 결제 락 | `@Version` 낙관락 → 409 | 비관락 대비 retry 로직 부담 |
| JWT | Access 15m + Refresh 14d, 1 user = 1 refresh | 멀티 디바이스 미지원 |
| Reuse Detection | Redis 키 부재 = 탈취 의심 → 강제 재로그인 | 잘못된 재로그인 강제 가능 |
| 대기열 | Redis Sorted Set + 워커 | ZPOPMIN + SADD 비원자 (사용자 재진입 허용) |
| 결제 PG | Mock + Port-Adapter (Hexagonal) | 실제 PG 미연동 |
| 시간 | UTC + Instant + Clock 주입 | 응답 KST 변환은 클라이언트 책임 |
| 로깅 | SLF4J + MDC requestId, PII 금지 | 구조화 로그 미적용 |
| 테스트 | Testcontainers (H2 금지) | 첫 실행 느림 + Docker 필수 |
| 부하 테스트 | k6 + Virtual Thread on/off + Pool 튜닝 비교 | seat-contention만 측정 (다른 시나리오 미측정) |

---

## 관련 자료

- 아키텍처 결정: [`docs/ADR.md`](./ADR.md)
- 시퀀스 다이어그램·디렉토리 구조: [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md)
- 부하 테스트 측정 결과: [`docs/BENCHMARK.md`](./BENCHMARK.md)
- 프로젝트 규칙: [`CLAUDE.md`](../CLAUDE.md)
