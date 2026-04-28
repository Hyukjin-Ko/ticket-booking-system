# Step 3: cancel-cleanup

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙. 특히 상태머신 메서드, Clock 주입, Redis 키 헬퍼, PII 로깅.
- `/docs/PRD.md` — 핵심 기능.
- `/docs/ARCHITECTURE.md` — **"사용자 능동 취소 시퀀스"** 와 **"PENDING 정리 워커 시퀀스"** 두 섹션 정확히 따를 것. 특히 사용자 취소는 **멱등(이미 CANCELLED여도 200)** 이며, **PAID만 409**.
- `/docs/ADR.md` — ADR-002 PENDING 만료 처리 (a/b 이중 안전망), ADR-003 상태머신.
- 이전 step 산출물:
  - `reservation/domain/Reservation.java` — `cancel(Clock)` 멱등 (CANCELLED→no-op, PAID→IllegalStateException)
  - `reservation/service/ReservationService.java` — `reserve`, `pay` 패턴
  - `reservation/repository/ReservationRepository.java` — native query 추가할 자리
  - `reservation/controller/ReservationController.java` — DELETE 엔드포인트 추가
  - `TicketApplication.java` — `@EnableScheduling` 이미 있는지 확인
  - `global/redis/RedisKeys.java`

## 작업

사용자 능동 취소 + PENDING 만료 정리 워커.

### 1. ReservationService.cancelByUser

`reservation/service/ReservationService.java`에 메서드 추가:

```java
@Transactional
public ReservationResponse cancelByUser(Long userId, Long reservationId) {
    Reservation r = reservationRepository.findById(reservationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

    if (!r.getUserId().equals(userId)) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    // PAID는 환불 정책 외 → 거부
    if (r.getStatus() == ReservationStatus.PAID) {
        throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATE);
    }

    // CANCELLED는 멱등 no-op (Reservation.cancel()이 내부에서 처리)
    if (r.getStatus() == ReservationStatus.CANCELLED) {
        return ReservationResponse.from(r);
    }

    // PENDING → 취소 + Redis 정리
    r.cancel(clock);
    redisTemplate.delete(RedisKeys.seat(r.getConcertId(), r.getSeatId()));
    redisTemplate.opsForValue().decrement(RedisKeys.count(userId, r.getConcertId()));
    log.info("user cancel reservationId={} userId={}", reservationId, userId);
    return ReservationResponse.from(r);
}
```

CRITICAL:
- 멱등성: 같은 reservation에 DELETE 두 번 → 두 번째도 200, 응답 동일.
- PAID는 거부 (환불 정책 외).
- 다른 유저 취소 시도 → 403.

### 2. ReservationController DELETE

`reservation/controller/ReservationController.java` 메서드 추가:

```java
@DeleteMapping("/{id}")
public ApiResponse<ReservationResponse> cancel(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long id) {
    return ApiResponse.success(
        reservationService.cancelByUser(userId, id),
        "예약이 취소되었습니다");
}
```

### 3. ReservationRepository — native query

`reservation/repository/ReservationRepository.java`에 추가:

```java
@Query(value = "SELECT * FROM reservation " +
               "WHERE status = 'PENDING' AND created_at < :cutoff " +
               "FOR UPDATE SKIP LOCKED",
       nativeQuery = true)
List<Reservation> findStaleForUpdate(@Param("cutoff") Instant cutoff);
```

> `FOR UPDATE SKIP LOCKED`: 동시에 워커가 여러 개 실행되어도 같은 row를 두 번 처리하지 않게. Postgres의 row-level lock 활용.

### 4. PendingReservationCleaner 워커

`src/main/java/com/harness/ticket/reservation/scheduler/PendingReservationCleaner.java`:

```java
@Component @RequiredArgsConstructor @Slf4j
public class PendingReservationCleaner {
    private static final Duration PENDING_THRESHOLD = Duration.ofMinutes(10);
    private final ReservationRepository reservationRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Scheduled(fixedDelay = 5 * 60 * 1000)   // 5분
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now(clock).minus(PENDING_THRESHOLD);
        List<Reservation> stale = reservationRepository.findStaleForUpdate(cutoff);
        if (stale.isEmpty()) return;

        for (Reservation r : stale) {
            r.cancel(clock);
            redisTemplate.delete(RedisKeys.seat(r.getConcertId(), r.getSeatId()));
            redisTemplate.opsForValue().decrement(RedisKeys.count(r.getUserId(), r.getConcertId()));
        }
        log.info("PendingReservationCleaner cancelled count={}", stale.size());
    }
}
```

CRITICAL:
- 워커 안에서 `Instant.now(clock)` 으로 cutoff 계산. 테스트 시 Clock fixed로 결정성 확보.
- `@Transactional` 메서드 안에서 `r.cancel(clock)` 호출 → JPA dirty checking으로 자동 update.
- Redis 정리 실패해도 throw 금지 (TTL 안전망 + 워커는 다음 tick에서 재시도).

### 5. 테스트

#### `reservation/service/ReservationCancelServiceTest` (Mockito)
- PENDING 취소 → `r.cancel`, redis delete, decrement.
- CANCELLED 재취소 → no-op (cancel 호출되지만 엔티티 메서드가 멱등). redis 호출은 발생 X (이미 정리됨)? → **수정**: CANCELLED 분기로 일찍 return하므로 redis 작업 없음. 검증.
- PAID → `INVALID_RESERVATION_STATE`.
- 다른 user → `FORBIDDEN`.
- 없음 → `RESERVATION_NOT_FOUND`.

#### `reservation/controller/ReservationCancelIT` (Testcontainers 통합)
사전: signup → login → reserve(seat 1,1).
시나리오:
1. **PENDING 취소**: `DELETE /reservations/{id}` → 200 + body status=CANCELLED. Redis seat 키 사라짐, count -1. DB cancelledAt 세팅.
2. **멱등 재호출**: 같은 endpoint 재호출 → 200 + 동일 응답. Redis 작업 추가 발생 안 함 (이미 정리됨).
3. **PAID 취소**: 결제 후 (`X-Mock-Pay-Result: success`) DELETE → 409 `INVALID_RESERVATION_STATE`.
4. **다른 유저**: 두 번째 사용자 토큰으로 첫 사용자 reservation DELETE → 403 `FORBIDDEN`.
5. **존재하지 않음**: id=999999 → 404 `RESERVATION_NOT_FOUND`.
6. **토큰 없음**: 401 `UNAUTHORIZED`.

#### `reservation/scheduler/PendingReservationCleanerIT` (Testcontainers 통합)
- `@TestConfiguration`으로 `Clock` Bean을 mutable하게 (시각 변경 가능). 또는 `Clock.fixed(...)` 두 번 주입 (전 / 후).
- 시나리오:
  1. **만료 cleanup**: 시각 T0 (clock=fixed T0), reserve 호출 → PENDING + Redis seat:1:1 + count=1. 시각 T1=T0+11분 (clock 변경) → cleaner.cleanup() 호출 → DB status=CANCELLED, Redis seat 키 사라짐, count=0.
  2. **유효한 PENDING은 건드리지 않음**: T0=시각 fixed, reserve → PENDING. T1=T0+5분 (만료 안 됨) → cleanup 호출 → 변화 없음 (DB PENDING 유지).
  3. **혼재**: 만료된 PENDING 1개 + 유효한 PENDING 1개 → cleanup → 만료된 것만 CANCELLED.
  4. **PAID는 건드리지 않음**: PENDING + cleanup이 이미 처리한 CANCELLED + 결제 완료된 PAID 혼재 → cleanup → 만료된 PENDING만 CANCELLED. 다른 row 변화 없음.
  5. **`FOR UPDATE SKIP LOCKED` 검증**: 두 번째 트랜잭션이 일부 row에 LOCK 걸어둔 상태에서 cleanup → SKIP된 row는 건드리지 않음, 나머지만 처리. (구현 난이도 있으므로 선택적 — 본 step에선 skip OK, 동작 확인은 코드 리뷰로).

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "*Cancel*" --tests "*Cleaner*"
```

위 명령들이 성공해야 한다. 이전 step·phase 모든 테스트도 깨지지 않아야 한다. **특히 동시성 테스트(`ReservationConcurrencyIT`)와 결제 멱등 테스트가 여전히 통과해야 함**.

## 검증 절차

1. 위 AC 커맨드 실행.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` "사용자 능동 취소 시퀀스" 멱등 동작 + "PENDING 정리 워커" 동작 모두 일치.
   - `/docs/ADR.md` ADR-002 (a)(b) 이중 안전망 둘 다 구현 — (a)는 step 2의 결제 만료 검증, (b)는 본 step의 워커.
   - `/CLAUDE.md` CRITICAL — 상태머신, Clock, Redis 키 헬퍼.
3. `phases/3-reservation/index.json`의 step 3을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "ReservationService.cancelByUser(멱등 200·PAID 409) + DELETE /reservations/{id} + PendingReservationCleaner @Scheduled 5분(FOR UPDATE SKIP LOCKED, Clock 주입) + 통합 시나리오(취소·멱등·PAID 거부·타유저·없음·만료 cleanup·혼재)"`
   - 실패/중단 시 error/blocked.
4. **phase 3 전체 완료** — index.json `status: "completed"` 자동 마킹.

## 금지사항

- 사용자 취소를 비멱등으로 만들지 마라 (CANCELLED 재호출 시 409 던지면 안 됨). 200 + 동일 응답.
- PAID 취소를 허용 금지 (환불은 MVP 외). 반드시 409.
- `Reservation.setStatus(CANCELLED)` 직접 대입 금지. 반드시 `r.cancel(clock)`.
- 워커에서 `Instant.now()` 직접 호출 금지. `Instant.now(clock)`.
- `findStaleForUpdate` 대신 `findAll().filter(...)` 같은 메모리 필터 금지. Postgres native query로 SKIP LOCKED 활용.
- 워커 fixedDelay 변경 금지 (5분 = 300_000ms). 짧게 하면 DB 부하, 길게 하면 stale 잔존.
- 워커에서 예외 throw 금지 (스케줄러가 죽음). try-catch 또는 트랜잭션 롤백으로 격리.
- 워커가 PAID·CANCELLED row를 건드리지 않도록 native query에서 필터(`status='PENDING'`).
- 워커 자체에 `@Transactional` 어노테이션 누락 금지.
- userId path/body 수신 금지. `@AuthenticationPrincipal`.
- 기존 phase 테스트 깨뜨리지 마라.
