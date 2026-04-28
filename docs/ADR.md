# Architecture Decision Records

## 철학
**동시성·일관성 스토리를 보여줄 수 있는 최소 구성을 최우선으로 한다.** 외부 의존성은 PostgreSQL과 Redis만. 기능 범위는 좁히고, "1000 TPS에서 어떻게 버티는지"를 수치로 증명할 수 있는 구조를 만든다.

---

### ADR-001: Spring Boot 3 + Java 21
**결정**: Spring Boot 3.x, Java 21, Gradle(Groovy DSL).
**이유**: 매칭 도메인(토스·인터파크 등)의 표준 스택. Java 21의 virtual thread로 I/O 바운드 워크로드 부하 스토리를 확장할 여지가 있다.
**트레이드오프**: 빌드·기동 시간이 무겁고, 로컬 iteration 속도가 Node 대비 느리다.

### ADR-002: 좌석 선점은 Redis `SET NX PX` 원자 연산
**결정**:
- 키: `seat:{showId}:{seatId}`, **값**: `userId` (선점 권한자 식별용. 결제·취소 시 일치 검증).
- TTL: 10분. 명령은 `SET key userId NX PX 600000` 단일 원자 연산.
- **선행 검증**: SET 전에 `SELECT FROM seat WHERE id=? AND show_id=?` 으로 좌석 존재·소속 공연 확인. 없으면 404 `SEAT_NOT_FOUND`.
- **1인당 동시 선점 한도**: 같은 공연에서 4좌석. 카운터 키 `count:user:{userId}:{showId}` (TTL 10분, INCR/DECR). 초과 시 409 `SEAT_LIMIT_EXCEEDED`.
- **보상 트랜잭션**: Redis SET 성공 후 DB insert 실패 시 `TransactionSynchronizationManager`의 afterCompletion 콜백으로 `DEL seat:*` + `DECR count:user:*`. DEL이 또 실패하면 ERROR 로깅 — TTL 10분이 안전망.
- **PENDING 만료 처리 — 이중 안전망**:
  - (a) 결제 시점에 Redis 키 부재 확인 → 자동 `Reservation.cancel()` + 410 `EXPIRED_RESERVATION`.
  - (b) `@Scheduled(fixedDelay=5min)` 정리 워커: PENDING + (created_at + 10분 < now) → 일괄 CANCELLED + Redis 키 DEL.

**이유**: DB 비관/낙관락 대비 경합 비용이 낮고 TTL로 유령 선점이 자동 해소된다. value에 userId를 담아 권한자 식별, 카운터로 abuse 방지, 보상·정리 워커로 Redis↔DB 정합성 보강.
**트레이드오프**: Redis 단일 장애점. 카운터는 INCR이 원자라 안전하나, "선점 → INCR → DB insert" 사이 부분 실패 시 카운터가 먼저 올라갈 수 있음 — 보상으로 DECR 보장.

### ADR-003: 예약 상태머신 — 3-state (PENDING / PAID / CANCELLED)
**결정**:
- 상태: `PENDING`, `PAID`, `CANCELLED` 3개. RESERVED 제거.
- 전이:
  - `PENDING → PAID`: `Reservation.pay()` (결제 성공)
  - `PENDING → CANCELLED`: `Reservation.cancel()` (사용자 취소·결제 실패·만료)
  - `PAID`, `CANCELLED`는 terminal
- 도메인 메서드 안에서만 상태 변경. 서비스·컨트롤러는 status 필드 직접 대입 금지. 허용되지 않은 전이는 `IllegalStateException` (HTTP 409 매핑).
- **멱등성**: PAID 상태에서 `pay()` 재호출 시 예외 없이 기존 응답 반환 (200). 네트워크 재시도 안전.
- **동시성 락**: `Reservation`에 `@Version` 낙관락. 동시 결제 충돌 시 `OptimisticLockException` → 409 `RESERVATION_CONFLICT`.
- **사용자 능동 취소**: `DELETE /reservations/{id}`는 PENDING 상태에서만 허용. PAID는 환불(MVP 제외), CANCELLED는 이미 취소.

**이유**: mock PG 환경에서 `PAYMENT_PROCESSING` 상태가 instant라 실효 없음 → 단순화. 상태가 적을수록 전이·테스트 케이스가 줄어든다. 실제 PG 연동 시 `PAYMENT_PROCESSING` 추가가 다음 마일스톤.
**트레이드오프**: PG 응답 대기 중 서버 장애 시 보상 워커 시연이 어려워짐 — mock 환경에선 무의미해 수용.

### ADR-004: JWT Access + Refresh (1 user = 1 refresh, Rotation)
**결정**:
- **Access**: HS256, 만료 15분. claims = `sub`(userId), `username`, `iat`, `exp`. 헤더 `Authorization: Bearer`.
- **Refresh**: HS256, 만료 14일. 응답 body로 전달.
- **저장**: Redis 키 `refresh:{userId}` (단일, jti 없음). value = refresh 토큰 자체. **1 user = 1 refresh**. 새 발급 시 덮어쓰기, logout = `DEL`. 모든 refresh 작업 O(1).
- **JWT secret**: env var `JWT_SECRET` 강제. 32바이트(256bit) 미만이면 startup fail. application.yml default 금지.
- **Rotation**: `POST /auth/refresh` 시 토큰 일치 확인 → 새 access + 새 refresh 발급 → Redis SET 덮어쓰기.
- **Reuse detection**: refresh JWT 서명·exp valid인데 Redis 키 없음 또는 value 불일치 → 탈취 의심 → `DEL refresh:{userId}` 후 401 `REUSE_DETECTED`. 사용자 강제 재로그인.
- **로그인 실패 응답**: ID 없음/비번 불일치 구분 없이 `INVALID_CREDENTIALS` 통일 (사용자 enumeration 방지).
- **비밀번호 정책**: 최소 8자, 영숫자만 검증. BCrypt strength 10. 평문·해시 모두 로깅 금지(ADR-009).
- **회원가입 정책**: 아이디·비밀번호만. 소셜·이메일 인증 없음. username 중복 시 409 `USERNAME_TAKEN`.
- **Brute-force 보호**: MVP 제외. 부하 테스트로 영향도만 측정.
- **Logout**: `POST /auth/logout` (Bearer access) → `DEL refresh:{userId}` → 204.

**이유**: Access 수명을 짧게 가져 탈취 피해 최소화. 1 user=1 refresh 단순화로 KEYS·SCAN 회피, O(1) 보장. Reuse detection은 표준 탈취 감지 패턴.
**트레이드오프**: 한 유저가 여러 디바이스에서 동시 로그인 못함. Redis 장애 시 재발급 불가. Brute-force 미방어.

### ADR-005: WaitingQueue = Redis Sorted Set
**결정**:
- **자료구조**: 키 `queue:{showId}` Sorted Set, score = 진입 timestamp(ms). 진입 `ZADD NX`, 순번 `ZRANK`, 워커 `ZPOPMIN N`.
- **활성화 조건**: `Concert.queueEnabled` flag (boolean). false면 큐 우회 (좌석 선점 시 `admit` 검사 생략). seed 데이터로 on/off.
- **admit Set**: `admit:{showId}` Redis Set. 워커가 ZPOPMIN 결과를 SADD. **TTL 10분** (좌석 선점과 동일).
- **워커 정책**: `@Scheduled` fixedDelay = `queue.tick-interval-sec` (기본 1초). 매 tick `queue.admits-per-tick` (기본 100명) admit. 둘 다 application.yml 노출.
- **ETA 계산**: `eta_sec = ceil(rank / admits_per_tick) * tick_interval_sec`. `GET /queue/{showId}/me` 응답에 포함.
- **큐 자체 TTL**: 공연 시작 시간 + 1시간으로 EXPIRE. 자동 정리.
- **워커 비원자성**: `ZPOPMIN → SADD` 사이에 워커 죽으면 사용자 유실. **MVP 정책**: 사용자가 다시 enter 가능 (큐는 ephemeral). 강한 보장이 필요하면 Lua 스크립트로 원자화 (다음 마일스톤).
- **selection 검증**: `POST /reservations` 호출 시 `Concert.queueEnabled=true`이면 `SISMEMBER admit:{showId} {userId}` 확인. 없으면 403 `NOT_ADMITTED`.

**이유**: FIFO + 순번 O(log N) + 원자적 pop을 단일 자료구조로. 운영 파라미터로 처리량 조절 가능.
**트레이드오프**: 워커 비원자성으로 사용자 유실 가능. ETA가 평균치라 실제 입장 시간 변동.

### ADR-006: 결제는 mock
**결정**:
- 외부 PG 미연동. `POST /reservations/{id}/pay` 응답:
  - 헤더 `X-Mock-Pay-Result: success|fail` 있으면 강제 (deterministic mode, 테스트용).
  - 헤더 없으면 90% 성공 / 10% 실패 (랜덤, 부하 테스트용).
- **재시도 정책**: 결제 실패 → `Reservation.cancel()` + `DEL seat`. 같은 reservation 재결제 불가 (terminal). 사용자는 좌석 선점부터 다시 시작.
- **Mock random seed**: `Random` 인스턴스 1개. 테스트에서 fixed seed 주입 가능하게 Bean으로 분리.

**이유**: 동시성·일관성 시연이 본 프로젝트 목표. PG 통합은 부수적. deterministic mode로 테스트 reproducibility 확보.
**트레이드오프**: 실제 결제 멱등성·웹훅·부분 환불 시나리오 단순화.

### ADR-007: 테스트 인프라는 Testcontainers
**결정**:
- 통합 테스트는 Testcontainers로 실제 Postgres/Redis를 띄워 검증. H2·임베디드 Redis 금지.
- `@Container static` + `withReuse(true)` + `~/.testcontainers.properties`의 `testcontainers.reuse.enable=true`로 컨테이너 재사용 (테스트 속도).
- CI는 GitHub Actions(Docker 자동 제공) 가정.
**이유**: 동시성·SQL 방언·Redis 커맨드가 프로덕션과 동일해야 부하 결과에 신뢰가 생긴다.
**트레이드오프**: 첫 실행 느림. Docker 필수.

### ADR-008: 시간·타임존은 UTC + `Instant`
**결정**:
- 모든 시간 값은 **UTC + `java.time.Instant`**.
- DB 컬럼은 `TIMESTAMPTZ` (Postgres). JPA 매핑은 `Instant`.
- JSON 직렬화는 ISO-8601 (`2026-04-28T12:34:56Z`). Jackson `JavaTimeModule` 등록, `WRITE_DATES_AS_TIMESTAMPS=false`.
- JVM 옵션 `-Duser.timezone=UTC`.
- KST 변환은 클라이언트 책임.
- 시간 의존 로직은 **`Clock` Bean 주입**. `Instant.now()` 직접 호출 금지. 테스트에선 `Clock.fixed(...)`로 시간 고정.

**이유**: 다중 타임존 혼재 버그(서머타임·DST·로컬 변환) 차단. Clock 주입으로 시간 의존 테스트(선점 만료 등)가 결정적.
**트레이드오프**: 응답이 UTC라 백엔드 디버깅 시 KST 헤매임. 클라이언트가 변환 책임.

### ADR-009: 로깅 정책
**결정**:
- SLF4J + Logback. 형식은 plain text (구조화 로그·JSON은 MVP 제외).
- **MDC**: 모든 HTTP 요청에 `requestId` (UUID) 자동 주입. `RequestIdFilter` 진입 시 set, 응답 후 clear.
- **PII 비로깅 규칙 (CRITICAL)**: 비밀번호 평문·BCrypt 해시·JWT 토큰 자체를 절대 로깅하지 마라. 사용자 식별은 userId(숫자)만 허용. 위반 시 보안 사고.
- **로그 레벨**:
  - INFO: 요청/응답 요약 (path, status, duration, userId).
  - WARN: `BusinessException` 발생.
  - ERROR: 5xx, 보상 트랜잭션 실패, reuse detection 발동 등.
- **3rd party 로그 레벨**: Hibernate SQL은 dev profile에서만 DEBUG, prod는 WARN 이상.

**이유**: PII 노출은 보안 사고. MDC requestId로 분산 추적 기초. 레벨 기준이 일관되어야 운영 노이즈 제어.
**트레이드오프**: 구조화 로그 부재 시 ELK·Loki 파싱에 추가 작업.
