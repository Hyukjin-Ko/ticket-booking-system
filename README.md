# Ticket Booking System

콘서트 티켓 예매 시스템의 백엔드 — 단일 노드 환경에서의 **재고 동시성·대기열·결제 일관성** 문제를 다룬 포트폴리오 프로젝트입니다.

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-336791) ![Redis](https://img.shields.io/badge/Redis-7-DC382D)

## 핵심 성과

| 지표 | 측정 결과 |
|---|---|
| **동시성 정확성** | 1000 VU가 동일 좌석에 동시 시도 → **정확히 1명 성공, 999명 거절** |
| Connection Pool 튜닝 효과 | p95 latency **1.64s → 686ms (-58%)** |
| 자동화 워크플로우 산출 | 5 phase / 80+ 커밋 / 34개 테스트 파일 |
| 테스트 인프라 | Testcontainers 기반 통합 테스트 (실제 Postgres·Redis) |

## 핵심 기능

1. **회원가입 / 로그인** — JWT Access(15분) + Refresh(14일) Rotation + **Reuse Detection** (탈취 감지)
2. **공연·좌석 조회** — 인증 필요, Spring Pageable 기반 페이지네이션
3. **좌석 선점** — Redis `SET NX PX` 단일 원자 연산 (TTL 10분), **1인당 4매** 한도
4. **결제** — Mock PG + Deterministic 헤더, **멱등 보장**, `@Version` 낙관락
5. **대기열** — Redis Sorted Set 기반 (FIFO + 순번 + ETA 계산), Admit Worker
6. **부하 테스트** — k6 시나리오 4종, **Virtual Thread on/off + Pool 튜닝 비교**

## 기술 스택

**Backend**: Java 21, Spring Boot 3.2, Spring Security, Spring Data JPA, Lombok
**Data**: PostgreSQL 15, Redis 7 (Lettuce), Flyway
**Auth**: JWT (jjwt 0.12), BCrypt (strength 10)
**Test**: JUnit 5, Mockito, Testcontainers, k6
**Build/Ops**: Gradle (Groovy DSL), Docker Compose, Spring Boot Actuator

## 아키텍처 하이라이트

- **Hexagonal Architecture (Port-Adapter)** — 결제는 `PaymentGateway` 인터페이스로 격리. 외부 PG(토스/카카오페이/네이버페이 등) 교체 시 도메인 코드 수정 없이 어댑터만 추가.
- **도메인 상태머신** — `Reservation` 엔티티 내부 메서드(`pay()` / `cancel()`)로 상태 전이 캡슐화. PAID 재호출 멱등, CANCELLED 멱등.
- **보상 트랜잭션** — Redis SET 성공 후 DB insert 실패 시 `TransactionSynchronizationManager.afterCompletion` 콜백으로 자동 복구. TTL 10분이 안전망.
- **PENDING 만료 처리 이중 안전망** — (1) 결제 시점 Redis 키 부재 검증으로 즉시 처리, (2) `@Scheduled` 5분 워커가 `FOR UPDATE SKIP LOCKED`로 일괄 정리.
- **시간 일관성** — 모든 시간은 UTC `Instant`. `Clock` Bean 주입으로 테스트 결정성 확보 (`LocalDateTime` 사용 금지).
- **PII 보호** — 비밀번호·BCrypt 해시·JWT 토큰 로그 출력 절대 금지. MDC `requestId`로 분산 추적 지원.

## 디렉토리 구조

```
src/main/java/com/harness/ticket/
├── global/                 # config, exception, response, security, redis, logging
├── auth/                   # User 도메인 + JWT (회원가입·로그인·refresh·logout)
├── concert/                # Concert·Seat 엔티티 + 조회 API
├── reservation/            # 좌석 선점·결제·취소·만료 정리 워커
│   ├── domain/             # Reservation (@Version, 3-state 상태머신)
│   ├── payment/            # PaymentGateway port + MockPaymentGatewayAdapter
│   └── scheduler/          # PendingReservationCleaner (@Scheduled)
└── queue/                  # 대기열 (Sorted Set) + AdmitWorker

src/main/resources/db/migration/      # Flyway V1~V6
loadtest/                              # k6 시나리오 4종 + helper
docs/                                  # ADR, ARCHITECTURE, BENCHMARK, INTERVIEW_QA
phases/                                # Phase별 step 명세 (자동화 워크플로우)
```

## Quick Start

### 사전 요구사항

- Java 21, Docker Desktop, k6 (`brew install k6`)

### 실행

```bash
# 1. 환경변수 셋업
cp .env.example .env
# .env 파일을 열어 JWT_SECRET 생성: openssl rand -base64 48

# 2. 인프라 기동 (Postgres + Redis)
docker compose up -d

# 3. 환경변수 로드 + 앱 실행
set -a && source .env && set +a
./gradlew bootRun

# 4. 헬스체크
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 테스트 실행

```bash
./gradlew test                                    # 단위 + 통합 테스트 일체
./gradlew test --tests "*ReservationConcurrencyIT"  # 동시성 테스트만
```

### 부하 테스트

```bash
# Smoke (10 VU, 30초)
k6 run loadtest/smoke.js

# 좌석 경합 (1000 VU 정확성 검증)
k6 run loadtest/scenario-seat-contention.js

# Virtual Thread 비교 측정
SPRING_THREADS_VIRTUAL_ENABLED=false ./gradlew bootRun &  # before
SPRING_THREADS_VIRTUAL_ENABLED=true HIKARI_MAX_POOL_SIZE=100 ./gradlew bootRun &  # after
```

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/PRD.md`](docs/PRD.md) | 요구사항·MVP 범위·API 스타일 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 디렉토리 구조·6개 시퀀스 다이어그램·컨벤션 |
| [`docs/ADR.md`](docs/ADR.md) | 9개의 아키텍처 결정 (Spring Boot 3.2 / Redis SET NX PX / JWT Rotation / Hexagonal 결제 / Testcontainers / UTC Instant / 로깅 정책 등) |
| [`docs/BENCHMARK.md`](docs/BENCHMARK.md) | k6 부하 테스트 측정 결과 + Virtual Thread/Pool 3-way 비교 분석 |
| [`docs/INTERVIEW_QA.md`](docs/INTERVIEW_QA.md) | **기술 결정 면접 Q&A 가이드** (9 카테고리, 18 결정 항목) |
| [`CLAUDE.md`](CLAUDE.md) | 프로젝트 규칙 (11개 CRITICAL 규칙) |

## 개발 워크플로우

본 프로젝트는 **AI 페어 프로그래밍 도구(Claude Code)** 기반의 자동화 워크플로우로 진행됐습니다. 사람의 판단(설계·결정·검증)과 도구의 효율(반복 코드 생성) 영역을 명확히 분리하는 방식을 시도했습니다.

| 영역 | 담당 |
|---|---|
| PRD·ADR·ARCHITECTURE 문서, 9개 기술 결정, 11개 CRITICAL 규칙 | **사람** |
| Phase별 step 명세 작성, 트레이드오프 의사결정 | **사람** |
| 측정 결과 분석, 자동화 출력 검증, 인적 오류 회고 | **사람** |
| 명세에 따른 코드 작성, 테스트 보일러플레이트, 반복 패턴 적용 | AI |

### 자동화 검증 사례

부하 테스트 단계에서 AI가 생성한 k6 helper의 username 생성 로직이 백엔드 validation regex(`^[가-힣A-Za-z0-9]+$`)와 충돌해 모든 signup이 실패하는 버그를 발견하고 즉시 수정한 사례가 있습니다. step 명세에 도메인 제약을 명시하지 않은 인적 오류로 회고했습니다 — [상세](docs/INTERVIEW_QA.md#9-1-ai-출력을-그대로-신뢰하지-않는다--도메인-제약-누락-사례).

자세한 워크플로우는 `phases/` 디렉토리의 step 명세 참조.

---

> ⚠️ 본 프로젝트는 **포트폴리오 목적의 MVP**입니다. 실제 PG 연동, 멀티 디바이스 로그인, 환불 정책 등은 의도적으로 제외했습니다. 트레이드오프 결정 배경은 [`docs/ADR.md`](docs/ADR.md) 참조.
