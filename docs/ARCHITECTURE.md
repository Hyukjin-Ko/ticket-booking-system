# 아키텍처

## 디렉토리 구조
```
src/main/java/com/harness/ticket/
├── global/
│   ├── config/           # Redis, Security, Jackson, Clock, MockRandom 등 Bean 설정
│   ├── exception/        # BusinessException + GlobalExceptionHandler
│   ├── response/         # ApiResponse, ErrorCode
│   ├── security/         # JwtProvider, JwtAuthFilter, SecurityConfig
│   ├── logging/          # RequestIdFilter (MDC)
│   └── redis/            # RedisKeys 헬퍼
├── auth/
│   ├── controller/
│   ├── service/
│   ├── domain/           # User 엔티티
│   ├── repository/
│   └── dto/
├── concert/              # Concert (queueEnabled flag 포함), Seat 엔티티 + 조회 API
├── reservation/
│   ├── controller/
│   ├── service/
│   ├── domain/           # Reservation 엔티티 (@Version 낙관락, 상태머신 메서드)
│   ├── repository/
│   ├── dto/
│   └── scheduler/        # PendingReservationCleaner (@Scheduled, 5분 간격)
└── queue/
    ├── controller/
    ├── service/
    ├── dto/
    └── scheduler/        # AdmitWorker (@Scheduled, tick-interval-sec)

src/main/resources/
├── application.yml
├── application-test.yml          # Testcontainers profile
└── db/migration/                 # Flyway: V1__baseline.sql, V2__user.sql, ...

src/test/java/...                 # 패키지 구조 미러링. 단위 + 통합 테스트.
loadtest/                         # k6 시나리오 + 리포트
docker-compose.yml                # 로컬 Postgres + Redis (healthcheck 포함)
```

## 패턴
- **Layered 아키텍처** — Controller → Service → Repository. Controller는 DTO 변환만, Service는 트랜잭션 경계 + 외부 I/O 오케스트레이션, Repository는 영속성.
- **도메인 로직은 엔티티 내부에** — 특히 상태 전이(`Reservation.pay()` 등). Anemic 도메인 금지.
- **Redis 접근은 Service 계층 전용** — Controller/Entity에서 `RedisTemplate` 직접 사용 금지. Redis 키는 `global/redis/RedisKeys`로만 생성.
- **시간은 Clock 주입** — `Instant.now()` 직접 호출 금지. 시간 의존 테스트는 `Clock.fixed(...)` 사용.
- **테스트 피라미드** — 도메인/Service 단위는 JUnit5 + Mockito. Repository/Redis 경로는 `@SpringBootTest` + Testcontainers (`@Container static withReuse(true)`).

## 데이터 흐름
```
Client
  │  Authorization: Bearer <jwt>
  ▼
RequestIdFilter (MDC requestId 주입)
  ▼
JwtAuthFilter ──(invalid/missing)──> 401
  │
  ▼
Controller ──> Service ──> Repository(JPA) ──> PostgreSQL
                 │
                 └──> RedisTemplate ──> Redis (seat lock / waiting queue / refresh)
```

### 좌석 선점 시퀀스
```
POST /reservations  {showId, seatId}
  ├─ JWT → userId 추출 (filter)
  ├─ Concert.queueEnabled=true 일 때만: SISMEMBER admit:{showId} userId
  │     └─ false → 403 NOT_ADMITTED
  ├─ count:user:{userId}:{showId} GET >= 4 → 409 SEAT_LIMIT_EXCEEDED
  ├─ SELECT seat WHERE id=? AND show_id=? → 없으면 404 SEAT_NOT_FOUND
  ├─ Redis SET seat:{showId}:{seatId} {userId} NX PX 600000
  │     ├─ 성공 →
  │     │     ├─ INCR count:user:{userId}:{showId} (만료 600초)
  │     │     └─ Reservation(PENDING, @Version=0) DB insert → 201
  │     └─ 실패 → 409 SEAT_ALREADY_HELD
  └─ DB insert 실패 시 (afterCompletion 콜백):
        ├─ DEL seat:{showId}:{seatId}
        ├─ DECR count:user:{userId}:{showId}
        └─ 둘 다 실패해도 TTL 10분이 안전망 (ERROR 로깅)
```

### 결제 확정 시퀀스
```
POST /reservations/{id}/pay   (header: X-Mock-Pay-Result?)
  ├─ @Transactional + @Version (낙관락)
  ├─ Reservation 조회 + userId 일치 검증 → 불일치 403
  ├─ status == PAID → 200 + 기존 응답 (멱등)
  ├─ status == CANCELLED → 409 INVALID_RESERVATION_STATE
  ├─ Redis GET seat:{showId}:{seatId}
  │     ├─ null → 만료됨 → Reservation.cancel() → 410 EXPIRED_RESERVATION
  │     └─ value != userId → 403 (이론상 발생 불가, 안전망)
  ├─ mock PG 호출 (X-Mock-Pay-Result 헤더 우선, 없으면 90/10 random):
  │     ├─ 성공: Reservation.pay() → DEL seat → DECR count → 200
  │     └─ 실패: Reservation.cancel() → DEL seat → DECR count → 402 PAYMENT_FAILED
  └─ OptimisticLockException catch → 409 RESERVATION_CONFLICT
```

### 사용자 능동 취소 시퀀스 (멱등)
```
DELETE /reservations/{id}
  ├─ @Transactional
  ├─ 조회 + userId 일치 검증 (불일치 → 403)
  ├─ status == PAID → 409 INVALID_RESERVATION_STATE (환불은 MVP 제외)
  ├─ status == CANCELLED → 200 (no-op, 멱등 보장)
  └─ status == PENDING → Reservation.cancel() → DEL seat → DECR count → 200
```

> **멱등성 보장**: 같은 reservation에 DELETE를 두 번 호출해도 두 번째는 200으로 응답. 클라이언트의 네트워크 재시도 안전.

### 인증 시퀀스
```
POST /auth/signup   {username, password}
  ├─ password 정책 (8자 이상, 영숫자) 검증 → 실패 시 400 INVALID_REQUEST
  ├─ username 중복 검사 → 409 USERNAME_TAKEN
  ├─ BCrypt(strength=10) 해시 후 INSERT
  └─ 201

POST /auth/login    {username, password}
  ├─ 사용자 조회 + BCrypt matches
  │     └─ 어느 단계든 실패 → 401 INVALID_CREDENTIALS (메시지 통일)
  ├─ access(15분) + refresh(14일) 발급
  ├─ Redis SET refresh:{userId} <refresh_token> EX 1209600  (덮어쓰기)
  └─ 200 {accessToken, refreshToken, expiresIn}

POST /auth/refresh  {refreshToken}
  ├─ JWT 서명·exp 검증 → 실패 시 401 INVALID_TOKEN
  ├─ Redis GET refresh:{userId}
  │     ├─ null 또는 value != 요청 토큰 → DEL refresh:{userId} → 401 REUSE_DETECTED
  │     └─ 일치 → 새 access + 새 refresh 발급 → Redis SET 덮어쓰기 → 200
  └─ ...

POST /auth/logout   (Bearer access)
  └─ DEL refresh:{userId} → 204
```

### 대기열 시퀀스
```
POST /queue/{showId}/enter  (Concert.queueEnabled=true 전제, false면 404)
  ├─ ZADD NX queue:{showId} <now_ms> <userId>
  ├─ EXPIRE queue:{showId} <show_start + 1h>  (최초 호출 시만)
  └─ 200 {position: ZRANK, eta}

GET /queue/{showId}/me  (Bearer access)
  ├─ rank = ZRANK queue:{showId} userId
  │     └─ null → 404 NOT_IN_QUEUE
  ├─ admitted = SISMEMBER admit:{showId} userId
  ├─ eta_sec = ceil(rank / admits_per_tick) * tick_interval_sec
  └─ 200 {position: rank, eta_sec, admitted}

(워커: AdmitWorker, @Scheduled fixedDelay=tick_interval_sec)
  for each Concert WHERE queueEnabled=true AND queue:{showId} 존재:
    users = ZPOPMIN queue:{showId} admits_per_tick
    for each user:
      SADD admit:{showId} user
      EXPIRE admit:{showId} 600
  (워커 비원자성 인지: ZPOPMIN 후 SADD 사이 죽으면 사용자 유실. 사용자 재진입 허용으로 보강)
```

### PENDING 정리 워커 시퀀스 (신규)
```
PendingReservationCleaner (@Scheduled fixedDelay=300_000ms)
  ├─ SELECT reservation WHERE status='PENDING' AND created_at < now()-INTERVAL '10 minutes' FOR UPDATE SKIP LOCKED
  ├─ for each:
  │     ├─ Reservation.cancel() → status=CANCELLED
  │     ├─ DEL seat:{showId}:{seatId}
  │     └─ DECR count:user:{userId}:{showId}
  └─ commit
```

## 상태 관리
- **PostgreSQL**: 영속 상태 — User, Concert(`queueEnabled` 포함), Seat, Reservation(`@Version`).
- **Redis**: 휘발 상태 —
  - 좌석 선점 락: `seat:{showId}:{seatId}` → value=`userId`, TTL 10분
  - 1인당 좌석 카운터: `count:user:{userId}:{showId}` → INT, TTL 10분
  - 대기열: `queue:{showId}` (Sorted Set), TTL=공연 시작+1시간
  - 입장 허가: `admit:{showId}` (Set), TTL 10분
  - Refresh 토큰: `refresh:{userId}` → value=토큰, TTL 14일
- **JVM 메모리**: 상태 저장 금지. 수평 확장 가능한 상태 유지.

## 컨벤션

### 패키징·DTO
- 패키지 최상위는 도메인(auth, concert, reservation, queue). 기술 레이어 최상위 패키지 금지.
- DTO 네이밍: `XxxRequest`, `XxxResponse`. 엔티티 직접 응답 금지.
- Redis 키 상수는 `RedisKeys.seat(showId, seatId)` 형식. 문자열 리터럴 금지.

### 입력 검증
- 모든 RequestDto에 `@Valid` 강제. `@NotBlank`, `@Size`, `@Pattern` 등 적용.
- 검증 실패는 `MethodArgumentNotValidException` → `GlobalExceptionHandler`에서 필드별 에러 목록 응답:
  ```json
  { "success": false, "code": "INVALID_REQUEST", "message": "검증 실패",
    "data": { "errors": [{"field":"password", "reason":"size must be >= 8"}] } }
  ```

### 페이지네이션
- 목록 API는 Spring `Pageable`. 기본 `size=20`, max=100. 쿼리 파라미터 `?page=0&size=20&sort=id,desc`.
- 응답은 Spring `Page<T>` → 변환 DTO `PageResponse<T>`로 (`content`, `page`, `size`, `totalElements`, `totalPages`).

### 시간
- 모든 시간 필드는 `Instant` (UTC). DB는 `TIMESTAMPTZ`. JSON ISO-8601.
- 시간 의존 로직은 `Clock` Bean 주입. `Instant.now()` / `LocalDateTime.now()` / `new Date()` 직접 호출 금지.

### 로깅 (ADR-009)
- SLF4J. **PII 절대 금지**: 비밀번호 평문·해시·JWT 토큰. userId만 허용.
- MDC `requestId`는 `RequestIdFilter`가 자동 주입.
- `BusinessException`은 WARN, 5xx는 ERROR + stack trace.

### 동시성 테스트
- Redis 선점·큐 입장·결제 확정처럼 경합이 있는 메서드는 `ExecutorService` + `CountDownLatch` 패턴으로 멀티스레드 테스트 작성.
- 검증 예: 100 스레드가 동일 좌석 선점 → 성공 1, 실패 99, DB Reservation row 1개.
