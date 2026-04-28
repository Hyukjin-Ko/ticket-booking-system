# Step 2: payment

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙. 특히 멱등성·낙관락·DTO·Clock·PII 로깅.
- `/docs/PRD.md` — 핵심 기능 6번 (결제 mock).
- `/docs/ARCHITECTURE.md` — **"결제 확정 시퀀스" 정확히 따라가라**.
- `/docs/ADR.md` — **ADR-006 전체** (mock 결제 + deterministic mode + 재시도 정책), ADR-003(멱등 pay, 낙관락).
- 이전 phase 산출물:
  - `reservation/domain/Reservation.java` — `pay(Clock)` / `cancel(Clock)` 멱등 + `@Version`
  - `reservation/service/ReservationService.java` — `reserve` 메서드 패턴
  - `reservation/repository/ReservationRepository.java`
  - `global/redis/RedisKeys.java` — `seat`, `count` 메서드
  - `global/exception/GlobalExceptionHandler.java` — `ObjectOptimisticLockingFailureException` → `RESERVATION_CONFLICT(409)` 핸들러 (phase 1에서 등록됨, ErrorCode는 phase 3-step 1에서 추가됨)
  - `global/security/JwtAuthFilter.java:55` — principal=`Long userId`

**"결제 확정 시퀀스"의 모든 분기**(PAID 멱등 / CANCELLED 거부 / Redis null=만료 / value 불일치=403 / mock 호출 / 성공·실패 분기 / 낙관락 충돌)를 빠짐없이 구현하라.

## 작업

결제 mock + 멱등성 + 낙관락 + Port-Adapter 패턴.

### 1. ErrorCode 항목 추가

`global/response/ErrorCode.java`에 추가:

```
PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", "결제에 실패했습니다"),     // 402
EXPIRED_RESERVATION(HttpStatus.GONE, "EXPIRED_RESERVATION", "선점 시간이 만료되었습니다"),  // 410
```

### 2. Random Bean 분리 (테스트 결정성)

`src/main/java/com/harness/ticket/global/config/RandomConfig.java`:

```java
@Configuration
public class RandomConfig {
    @Bean
    public Random random() {
        return new SecureRandom();
    }
}
```

테스트에선 `@TestConfiguration`으로 `Random(fixedSeed)` 주입 또는 Mockito stub.

### 3. Port: `PaymentGateway` 인터페이스

`src/main/java/com/harness/ticket/reservation/payment/PaymentGateway.java`:

```java
public interface PaymentGateway {
    /**
     * 결제 호출.
     * @param reservationId 예약 식별자
     * @param mockResultHeader X-Mock-Pay-Result 헤더 값 ("success"|"fail"|null)
     * @return 결제 성공 시 true, 실패 시 false
     */
    boolean pay(Long reservationId, String mockResultHeader);
}
```

> Port는 도메인 패키지(`reservation/payment/`)에 둔다. 의존성 방향: 어댑터 → 포트.

### 4. Adapter: `MockPaymentGatewayAdapter`

`src/main/java/com/harness/ticket/reservation/payment/MockPaymentGatewayAdapter.java`:

```java
@Component @RequiredArgsConstructor @Slf4j
public class MockPaymentGatewayAdapter implements PaymentGateway {
    private static final int SUCCESS_RATE_OUT_OF_10 = 9;   // 90%
    private final Random random;

    @Override
    public boolean pay(Long reservationId, String mockResultHeader) {
        if ("success".equals(mockResultHeader)) {
            log.info("mock payment FORCED success reservationId={}", reservationId);
            return true;
        }
        if ("fail".equals(mockResultHeader)) {
            log.info("mock payment FORCED fail reservationId={}", reservationId);
            return false;
        }
        boolean ok = random.nextInt(10) < SUCCESS_RATE_OUT_OF_10;
        log.info("mock payment random reservationId={} result={}", reservationId, ok ? "success" : "fail");
        return ok;
    }
}
```

> 어댑터는 인프라 패키지에 둘 수도 있으나, 본 프로젝트는 도메인 패키지에 함께 둔다 (단순성). 향후 어댑터 다수 시 `reservation/payment/adapter/`로 분리 검토.

### 5. ReservationService 메서드 추가

`reservation/service/ReservationService.java`에 추가 (`PaymentGateway`도 주입):

```java
@Transactional
public ReservationResponse pay(Long userId, Long reservationId, String mockHeader) {
    Reservation r = reservationRepository.findById(reservationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

    if (!r.getUserId().equals(userId)) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    // 멱등: 이미 PAID이면 동일 응답
    if (r.getStatus() == ReservationStatus.PAID) {
        return ReservationResponse.from(r);
    }
    if (r.getStatus() == ReservationStatus.CANCELLED) {
        throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATE);
    }

    // Redis 키 부재 = 만료
    String seatKey = RedisKeys.seat(r.getConcertId(), r.getSeatId());
    String stored = redisTemplate.opsForValue().get(seatKey);
    if (stored == null) {
        r.cancel(clock);
        throw new BusinessException(ErrorCode.EXPIRED_RESERVATION);
    }
    if (!stored.equals(userId.toString())) {
        // 이론상 발생 불가 (선점한 사람만이 결제 가능). 안전망.
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    boolean ok = paymentGateway.pay(reservationId, mockHeader);
    if (ok) {
        r.pay(clock);
    } else {
        r.cancel(clock);
    }
    redisTemplate.delete(seatKey);
    redisTemplate.opsForValue().decrement(RedisKeys.count(userId, r.getConcertId()));

    if (!ok) {
        throw new BusinessException(ErrorCode.PAYMENT_FAILED);
    }
    return ReservationResponse.from(r);
}
```

CRITICAL 규칙:
- 멱등성 처리는 엔티티 `pay()` 안에서 이미 PAID no-op이지만, 서비스 진입 시점에서도 status==PAID 분기 → 기존 응답을 그대로 반환. 두 군데가 다 안전망.
- Redis 키 부재 시 자동 cancel + 410. 사용자가 새 reservation을 만들도록 유도.
- `OptimisticLockException` (이름: `ObjectOptimisticLockingFailureException`)은 catch하지 마라 — `GlobalExceptionHandler`가 자동 매핑(409 RESERVATION_CONFLICT).
- 결제 실패 시에도 Redis 키와 count 정리 (정상 흐름과 동일). 선점 잔존 방지.

### 6. ReservationController 메서드 추가

```java
@PostMapping("/{id}/pay")
public ApiResponse<ReservationResponse> pay(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long id,
        @RequestHeader(value = "X-Mock-Pay-Result", required = false) String mockHeader) {
    return ApiResponse.success(reservationService.pay(userId, id, mockHeader), "결제가 완료되었습니다");
}
```

### 7. 테스트

#### `reservation/payment/MockPaymentGatewayAdapterTest` (단위)
- 헤더 "success" → true
- 헤더 "fail" → false
- 헤더 null + Random fixed seed (예: `new Random(42)`) → 결정적 결과 검증
  - `random.nextInt(10)`을 fixed seed 기반으로 호출했을 때의 첫 결과를 확인하고, 9 미만이면 success, 9면 fail. 테스트는 시드 값으로 예상되는 결과를 명시적으로 검증.

#### `reservation/service/ReservationPaymentServiceTest` (Mockito)
- 정상 결제 → `r.pay(clock)` 호출, Redis delete + decrement, 응답 PAID
- 결제 실패 → `r.cancel(clock)` 호출, Redis delete + decrement, `PAYMENT_FAILED` 예외
- PAID 재호출 (멱등) → mock gateway 호출되지 않음, 기존 응답 반환
- CANCELLED → `INVALID_RESERVATION_STATE`
- Redis 키 null → `r.cancel(clock)` 후 `EXPIRED_RESERVATION`
- 다른 userId → `FORBIDDEN`
- 존재하지 않는 reservation → `RESERVATION_NOT_FOUND`

#### `reservation/controller/ReservationPaymentIT` (Testcontainers 통합)
사전: signup → login → seat 1,1 선점 (PENDING) → reservationId 획득.
시나리오:
1. **정상 결제 (deterministic)**: `POST /reservations/{id}/pay` + 헤더 `X-Mock-Pay-Result: success` → 200, body status=PAID. Redis seat 키 사라짐, count -1. DB reservation status=PAID, paidAt 세팅.
2. **결제 실패**: 헤더 `fail` → 402 + `code:"PAYMENT_FAILED"`. DB status=CANCELLED.
3. **멱등성**: 1번 후 같은 endpoint 재호출 → 200 + 동일 응답 (PAID). mock gateway 호출 카운트는 1번만 (Mockito spy 또는 호출 카운트 추적). DB status 그대로 PAID.
4. **만료**: Redis 키를 수동 삭제 (`redisTemplate.delete(...)`) 후 pay → 410 `EXPIRED_RESERVATION` + DB status=CANCELLED.
5. **다른 유저**: 다른 사용자 access 토큰으로 pay → 403 `FORBIDDEN`.
6. **존재하지 않는 reservation**: id=999999 → 404 `RESERVATION_NOT_FOUND`.
7. **CANCELLED 결제 시도**: 사용자가 사전에 reservation을 cancel(또는 결제 실패로 CANCELLED) → pay 재시도 → 409 `INVALID_RESERVATION_STATE`.
8. **낙관락 충돌**: `ExecutorService(2)` + `CountDownLatch`로 같은 reservation에 동시 pay 호출 (둘 다 valid 요청) → 1 성공(200, PAID), 1 충돌(409, `RESERVATION_CONFLICT`). Mockito 또는 sleep 트릭으로 경합 유도. mock gateway는 둘 다 success 헤더로.

> **시간 의존 테스트는 Clock fixed로 결정성 확보**. `@TestConfiguration`으로 `Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC)` 주입.

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "*Payment*" --tests "*ReservationPayment*"
```

위 명령들이 성공해야 한다. 이전 step·phase 테스트도 깨지지 않아야 한다.

## 검증 절차

1. 위 AC 커맨드 실행.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` "결제 확정 시퀀스"의 모든 분기 구현.
   - `/docs/ADR.md` ADR-006(deterministic mode 헤더, 재시도 정책 = 새 선점부터).
   - `/CLAUDE.md` CRITICAL — 멱등성, 낙관락, Clock, PII 로깅 금지.
   - **Port-Adapter**: `PaymentGateway` 인터페이스 + `MockPaymentGatewayAdapter` 구현체 분리. 도메인은 인터페이스만 의존.
3. `phases/3-reservation/index.json`의 step 2를 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "PaymentGateway port + MockPaymentGatewayAdapter(헤더 강제·90/10 random·SecureRandom) + ReservationService.pay(멱등 PAID·만료 410·낙관락 자동 매핑·실패 시 cancel+정리) + POST /reservations/{id}/pay + 통합 8 시나리오(정상·실패·멱등·만료·타유저·없음·CANCELLED·낙관락충돌)"`
   - 실패/중단 시 error/blocked.

## 금지사항

- `OptimisticLockException`을 서비스에서 catch 금지. `GlobalExceptionHandler`가 처리. catch하면 의도된 409 매핑 깨진다.
- 결제 실패 시 Redis 정리 누락 금지. seat 키와 count 카운터 둘 다 정리.
- mock random에 `Math.random()` 사용 금지. 반드시 주입된 `Random` Bean.
- `MockPaymentGatewayAdapter`를 Spring 외부에서 직접 `new` 금지. 항상 DI.
- 컨트롤러에서 mock 분기 처리 금지 (예: 헤더 보고 컨트롤러가 결정). 헤더는 서비스를 통해 어댑터로 전달.
- 같은 reservation에 결제 두 번 시 두 번 다 mock gateway 호출 금지. 두 번째는 멱등 분기로 즉시 반환.
- userId를 path/body에서 받지 마라. `@AuthenticationPrincipal`.
- 결제 성공 응답 message는 "결제가 완료되었습니다" 단일. 다른 변형 금지.
- 사용자 취소·만료 정리 워커 만들지 마라. 이유: step 3.
- 토큰·사용자 카드 정보·민감 데이터 로깅 금지 (mock이라 카드정보는 없으나 일반 원칙).
- 기존 phase 테스트 깨뜨리지 마라.
