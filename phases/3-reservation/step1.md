# Step 1: seat-reservation (★ 핵심)

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — **모든 11개 CRITICAL 규칙**. 특히 상태머신·Redis 원자 연산·키 헬퍼·DTO·Clock·보상 트랜잭션·낙관락.
- `/docs/PRD.md` — 핵심 기능 3·4번.
- `/docs/ARCHITECTURE.md` — **"좌석 선점 시퀀스" 정확히 따라가라**. Redis 키 정의, 보상 다이어그램 포함.
- `/docs/ADR.md` — **ADR-002 전체** (Redis SET NX PX, value=userId, 1인 4매, 보상, PENDING 만료), **ADR-003 전체** (3-state 상태머신, 멱등 pay, @Version 낙관락).
- 이전 phase 산출물:
  - `concert/domain/Concert.java`, `Seat.java`
  - `concert/repository/SeatRepository.java` — `findByIdAndConcertId(...)` 좌석 검증에 사용
  - `global/redis/RedisKeys.java` — `seat(showId, seatId)`, `count(userId, showId)` 메서드 존재
  - `global/response/ErrorCode.java` — 항목 추가
  - `global/exception/GlobalExceptionHandler.java` — `ObjectOptimisticLockingFailureException` 핸들러는 phase 1-setup step 1에 이미 등록됨 → 본 step에선 활용만
  - `global/security/JwtAuthFilter.java:55` — principal=`Long userId`
  - `auth/domain/User.java` — 엔티티 패턴 (Clock 주입 + 정적 팩토리)
  - `src/test/java/com/harness/ticket/support/IntegrationTestSupport.java` — Testcontainers 베이스

ARCHITECTURE.md "좌석 선점 시퀀스"의 모든 단계(queue 검사 / count 검사 / seat 존재 / SET NX / INCR / DB insert / 보상)를 빠짐없이 구현하라. 단, queue 검사는 `Concert.queueEnabled=true` 일 때만 하고, **본 step에서는 SISMEMBER admit 검사를 false 분기로 두지 않는다** (admit Set이 phase 4에서 만들어짐). queueEnabled가 true인 경우에 대한 처리는 phase 4에서 추가하기로 하고, 본 step은 `queueEnabled=true`이면 "phase 4 미구현"이라는 명확한 사유로 503 또는 임시로 통과시키는 등의 선택을 하라 — **권장: queueEnabled=true인 경우 본 step에선 통과(검사 skip), 주석으로 "phase 4-queue에서 SISMEMBER 검사 추가 예정" 명시**.

## 작업

Reservation 엔티티 + 좌석 선점 + 1인 4매 + 보상 트랜잭션 + 동시성 테스트.

### 1. Flyway V5 — Reservation 테이블

`src/main/resources/db/migration/V5__reservation.sql`:

```sql
CREATE TABLE reservation (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    concert_id    BIGINT       NOT NULL REFERENCES concert(id),
    seat_id       BIGINT       NOT NULL REFERENCES seat(id),
    status        VARCHAR(20)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    paid_at       TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ
);

CREATE INDEX idx_reservation_status_created ON reservation (status, created_at);
CREATE INDEX idx_reservation_user           ON reservation (user_id);
```

> `seat_id`에 unique 제약 두지 마라 — 같은 좌석이 여러 reservation row를 가질 수 있다 (이전 시도 CANCELLED 후 재선점). 동시성 보장은 Redis가 담당.

### 2. ReservationStatus enum

`src/main/java/com/harness/ticket/reservation/domain/ReservationStatus.java`:

```java
public enum ReservationStatus {
    PENDING, PAID, CANCELLED
}
```

### 3. Reservation 엔티티

`src/main/java/com/harness/ticket/reservation/domain/Reservation.java`:

요구사항:
- `@Entity @Table(name="reservation")`, `@Getter`, `@NoArgsConstructor(access=PROTECTED)`.
- 필드:
  - `Long id`, `Long userId`, `Long concertId`, `Long seatId`
  - `@Enumerated(EnumType.STRING) ReservationStatus status`
  - `@Version Long version`
  - `Instant createdAt`, `Instant paidAt`, `Instant cancelledAt`
- 정적 팩토리 `Reservation.create(userId, concertId, seatId, clock)` → status=PENDING, createdAt=Instant.now(clock).
- 메서드:
  - `void pay(Clock clock)`:
    - status == PAID → **no-op (멱등)**, 예외 X
    - status == CANCELLED → `IllegalStateException("이미 취소된 예약은 결제할 수 없습니다")`
    - status == PENDING → status=PAID, paidAt=Instant.now(clock)
  - `void cancel(Clock clock)`:
    - status == CANCELLED → **no-op (멱등)**, 예외 X
    - status == PAID → `IllegalStateException("결제 완료된 예약은 취소할 수 없습니다")` (환불 MVP 제외)
    - status == PENDING → status=CANCELLED, cancelledAt=Instant.now(clock)
- `setStatus`, `setVersion` 등 setter 노출 금지.

> CRITICAL: 멱등성을 엔티티 메서드 안에서 처리. 서비스 계층에서 if/else 분기 추가 금지.

### 4. ReservationRepository

`src/main/java/com/harness/ticket/reservation/repository/ReservationRepository.java`:

```java
public interface ReservationRepository extends JpaRepository<Reservation, Long> {}
```

> 만료 정리 native query는 step 3에서 추가.

### 5. ErrorCode 항목 추가

`global/response/ErrorCode.java`에 추가:

```
SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND", "좌석을 찾을 수 없습니다"),
SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "SEAT_ALREADY_HELD", "이미 선점된 좌석입니다"),
SEAT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "SEAT_LIMIT_EXCEEDED", "1인당 4좌석까지만 선점할 수 있습니다"),
RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다"),
RESERVATION_CONFLICT(HttpStatus.CONFLICT, "RESERVATION_CONFLICT", "동시 처리 충돌이 발생했습니다"),
INVALID_RESERVATION_STATE(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", "예약 상태에서 허용되지 않는 작업입니다"),
```

> step 2/3에서 `PAYMENT_FAILED`, `EXPIRED_RESERVATION` 등 추가.

### 6. DTO

`src/main/java/com/harness/ticket/reservation/dto/`:

#### `ReservationRequest.java`
```java
public record ReservationRequest(
    @NotNull Long concertId,
    @NotNull Long seatId
) {}
```

#### `ReservationResponse.java`
```java
public record ReservationResponse(
    Long id,
    Long concertId,
    Long seatId,
    ReservationStatus status,
    Instant createdAt
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(r.getId(), r.getConcertId(), r.getSeatId(),
            r.getStatus(), r.getCreatedAt());
    }
}
```

### 7. ReservationService

`src/main/java/com/harness/ticket/reservation/service/ReservationService.java`:

요구사항:
- `@Service @RequiredArgsConstructor @Slf4j`.
- 주입: `ReservationRepository`, `SeatRepository`, `ConcertRepository`, `StringRedisTemplate`, `Clock`.
- 상수: `private static final long SEAT_TTL_SEC = 600;` (10분), `private static final int SEAT_LIMIT = 4;`.
- 메서드:

```java
@Transactional
public ReservationResponse reserve(Long userId, ReservationRequest req) {
    Long concertId = req.concertId();
    Long seatId    = req.seatId();

    // 1. queueEnabled true이면 phase 4에서 admit 검사 추가 예정 — 현 step에선 통과
    Concert concert = concertRepository.findById(concertId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));
    // (queueEnabled 체크 — phase 4-queue에서 SISMEMBER admit:{showId} userId 검사 추가)

    // 2. 1인 4매 한도 체크
    String countKey = RedisKeys.count(userId, concertId);
    String currentCount = redisTemplate.opsForValue().get(countKey);
    if (currentCount != null && Integer.parseInt(currentCount) >= SEAT_LIMIT) {
        throw new BusinessException(ErrorCode.SEAT_LIMIT_EXCEEDED);
    }

    // 3. 좌석 존재·소속 검증
    Seat seat = seatRepository.findByIdAndConcertId(seatId, concertId)
        .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

    // 4. Redis SET NX PX (단일 원자)
    String seatKey = RedisKeys.seat(concertId, seatId);
    Boolean acquired = redisTemplate.opsForValue()
        .setIfAbsent(seatKey, userId.toString(), Duration.ofSeconds(SEAT_TTL_SEC));
    if (!Boolean.TRUE.equals(acquired)) {
        throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD);
    }

    // 5. 카운터 증가 + TTL (count 키도 만료 600초)
    Long newCount = redisTemplate.opsForValue().increment(countKey);
    if (newCount != null && newCount == 1L) {
        redisTemplate.expire(countKey, Duration.ofSeconds(SEAT_TTL_SEC));
    }

    // 6. 보상 트랜잭션 등록 — DB 실패 시 Redis 정리
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        redisTemplate.delete(seatKey);
                        redisTemplate.opsForValue().decrement(countKey);
                    } catch (Exception e) {
                        log.error("Compensation failed for seatKey={} countKey={}", seatKey, countKey, e);
                    }
                }
            }
        });
    }

    // 7. Reservation insert (PENDING)
    Reservation saved = reservationRepository.save(
        Reservation.create(userId, concertId, seatId, clock));

    return ReservationResponse.from(saved);
}
```

CRITICAL 규칙:
- Redis SET은 **반드시 `setIfAbsent` + `Duration` 단일 호출**. `EXISTS` → `SET` 분리 금지.
- 좌석 검증을 SET 호출 전에 한다 (잘못된 seatId로 키만 생성되는 사고 방지).
- 보상은 `afterCompletion` 콜백 — `STATUS_COMMITTED` 가 아닌 모든 경우 (rollback, unknown) 정리.
- 보상 자체가 실패해도 throw 금지 (TTL 안전망). 단, ERROR 로그 필수.
- userId·concertId·seatId 외 어떤 식별자도 로그에 출력 금지 — 토큰 등 PII 금지.

### 8. ReservationController

`src/main/java/com/harness/ticket/reservation/controller/ReservationController.java`:

```java
@RestController @RequestMapping("/reservations") @RequiredArgsConstructor
public class ReservationController {
    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> reserve(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ReservationRequest req) {
        ReservationResponse res = reservationService.reserve(userId, req);
        return ResponseEntity.status(201).body(ApiResponse.success(res, "좌석이 선점되었습니다"));
    }
}
```

### 9. 테스트

#### `reservation/domain/ReservationTest` (POJO 단위)
- `create` → status=PENDING, createdAt=clock 시각.
- `pay`: PENDING → PAID + paidAt 세팅. PAID → no-op (예외 X, paidAt 변경 X). CANCELLED → `IllegalStateException`.
- `cancel`: PENDING → CANCELLED + cancelledAt. CANCELLED → no-op. PAID → `IllegalStateException`.

#### `reservation/service/ReservationServiceTest` (Mockito)
- 정상 reserve: 모든 단계 호출, save 결과 반환.
- count 4 초과 → `SEAT_LIMIT_EXCEEDED`, Redis SET 호출되지 않음.
- 좌석 없음 → `SEAT_NOT_FOUND`.
- Redis SET 실패 (이미 점유) → `SEAT_ALREADY_HELD`.
- 보상: DB save에서 강제 RuntimeException → `afterCompletion` 콜백이 호출되어 redis delete + decrement (Spring 트랜잭션 모킹 또는 `TransactionSynchronizationManager` 직접 trigger).

#### `reservation/controller/ReservationConcurrencyIT` (Testcontainers, **★ 동시성 검증**)
- `IntegrationTestSupport` 베이스 + signup으로 사용자 1명 + access 토큰 획득.
- `ExecutorService(100)` + `CountDownLatch(1)` 패턴:
  - 100 스레드가 동일 (concertId=1, seatId=1) 으로 `POST /reservations` 동시 호출.
  - latch.countDown() 후 일제히 출발.
- assertions:
  - HTTP 201 응답 카운트 = **1**
  - HTTP 409 (`SEAT_ALREADY_HELD`) 카운트 = **99**
  - DB `reservation` 테이블 PENDING 카운트 = 1
  - Redis `seat:1:1` 값 존재 + value = userId
  - Redis `count:user:{userId}:1` = "1"
  - 이 검증을 통해 "1 성공·99 실패는 정확성 보장"이라는 의도 명문화 (테스트 이름·메시지에 명시)

#### `reservation/controller/ReservationLimitIT`
- 같은 사용자가 좌석 4개 순차 선점 (1,1 → 1,2 → 1,3 → 1,4) → 모두 201.
- 5번째 (1,5) → 409 `SEAT_LIMIT_EXCEEDED`.
- Redis `count:user:{userId}:1` = "4".

#### `reservation/controller/ReservationCompensationIT`
- DB save를 강제 실패시키는 시나리오 (예: 동시 다른 트랜잭션이 unique constraint 발생 또는 테스트용으로 ConcertRepository를 spy로 만들어 throw).
- 또는 트랜잭션 강제 rollback (`@Rollback`):
  - reserve 호출 → 트랜잭션 rollback → afterCompletion → Redis 키 삭제됨, count 감소됨 검증.
- 보상이 호출되었음을 검증 (Redis `EXISTS seat:...` = 0, count = 0 또는 이전 값).

#### `reservation/controller/ReservationAuthIT`
- 토큰 없이 `POST /reservations` → 401 UNAUTHORIZED.
- 다른 유저의 access 토큰 → 정상 처리 (자신의 reservation 생성, 권한 체크는 결제·취소에서).

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "com.harness.ticket.reservation.*"
./gradlew test --tests "*ReservationConcurrencyIT"   # 동시성 별도 강조
```

위 명령들이 성공해야 한다. 이전 phase의 모든 테스트도 깨지지 않아야 한다.

## 검증 절차

1. 위 AC 커맨드 실행. **`ReservationConcurrencyIT`가 통과해야 step 완료** (1 성공·99 실패 검증).
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` "좌석 선점 시퀀스" 단계 모두 구현.
   - `/docs/ADR.md` ADR-002, ADR-003 모든 항목 준수.
   - `/CLAUDE.md` CRITICAL — 상태머신 엔티티 메서드, Redis 원자 연산, 키 헬퍼, DTO, Clock, 보상.
3. `phases/3-reservation/index.json`의 step 1을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "Reservation 엔티티(@Version, 3-state 상태머신, pay/cancel 멱등) + V5 스키마 + ReservationService(SET NX PX 단일·1인4매·좌석검증·afterCompletion 보상) + POST /reservations(authenticated) + 동시성 테스트 100스레드→1성공/99실패 통과 + 한도/보상 IT"`
   - 실패/중단 시 error/blocked.

## 금지사항

- Redis 선점에서 `EXISTS` → `SET` 분리 호출 금지. 반드시 `setIfAbsent`(SETNX) + `Duration` 단일 원자.
- `Reservation.setStatus(...)` 또는 status 필드 직접 대입 금지. 반드시 `pay()` / `cancel()` 메서드.
- `Reservation`에 `@Setter` 추가 금지.
- 멱등성 처리를 서비스 계층에 두지 마라 — `pay()`, `cancel()` 메서드 안에서.
- `Concert` ↔ `Reservation`, `Seat` ↔ `Reservation` JPA 매핑(`@ManyToOne`/`@OneToMany`) 금지. Long id 필드만.
- 보상 트랜잭션 누락 금지. `afterCompletion`으로 등록.
- 보상에서 예외 throw 금지 (ERROR 로그만, TTL 안전망 신뢰).
- 결제 / 사용자 취소 / 만료 정리 워커 만들지 마라. 이유: step 2/3에서 일관 추가.
- queueEnabled=true 검사 로직을 본 step에서 완성하지 마라 — phase 4-queue에서 admit Set 추가. 본 step은 통과(검사 skip) + 주석으로 명시.
- userId를 path/body에서 받지 마라. 반드시 `@AuthenticationPrincipal Long userId`로 SecurityContext에서 추출.
- 1인 4매 한도 체크 누락 금지.
- 좌석 검증 누락 금지 (`SeatRepository.findByIdAndConcertId`).
- 기존 phase의 테스트를 깨뜨리지 마라.
