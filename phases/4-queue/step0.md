# Step 0: queue-domain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙 11개. 특히 Redis 키 헬퍼, Clock 주입, DTO 응답.
- `/docs/PRD.md` — 핵심 기능 5번 (대기열).
- `/docs/ARCHITECTURE.md` — **"대기열 시퀀스" 정확히 따라가라**.
- `/docs/ADR.md` — **ADR-005 전체** (Sorted Set, queueEnabled flag, ETA 공식, queue TTL=공연 시작+1시간, 워커 비원자성).
- 이전 phase 산출물:
  - `concert/domain/Concert.java` — `queueEnabled` boolean 필드, `startsAt` Instant 존재
  - `concert/repository/ConcertRepository.java` — 추가 메서드 자리
  - `global/redis/RedisKeys.java` — `queue(showId)`, `admit(showId)` 메서드 이미 있음
  - `global/security/JwtAuthFilter.java:55` — principal=`Long userId`
  - `global/response/ApiResponse.java`, `ErrorCode.java`
  - `src/main/resources/db/migration/V4__seed_concert.sql` — 기존 seed (queueEnabled=false 공연 1개)

ARCHITECTURE.md "대기열 시퀀스"의 모든 분기를 빠짐없이 구현하되, **본 step에서는 워커(`AdmitWorker`)와 admit Set 생성 로직은 만들지 마라** — step 1에서 처리. 본 step은 queue Sorted Set 입장·순번·ETA만.

## 작업

queue:{showId} Sorted Set 입장 + 순번 조회 + ETA 계산 + queueEnabled flag 검증.

### 1. Flyway V6 — Seed (queueEnabled=true 공연 추가)

`src/main/resources/db/migration/V6__seed_queued_concert.sql`:

```sql
-- 큐 시연용 공연 (concert id=2, queueEnabled=true)
INSERT INTO concert (title, starts_at, queue_enabled)
VALUES ('하네스 페스티벌 2026 - 인기 공연', NOW() + INTERVAL '30 days', true);

-- 100석
INSERT INTO seat (concert_id, section, row_no, col_no)
SELECT 2, 'A', r.row_no, c.col_no
FROM generate_series(1, 10) AS r(row_no)
CROSS JOIN generate_series(1, 10) AS c(col_no);
```

### 2. QueueProperties

`src/main/java/com/harness/ticket/global/config/QueueProperties.java`:

```java
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(int tickIntervalSec, int admitsPerTick) {}
```

`TicketApplication`에 `@ConfigurationPropertiesScan`이 이미 있으면 자동 등록. 없으면 `@EnableConfigurationProperties(QueueProperties.class)` 명시.

### 3. application.yml

기존 yaml에 추가:

```yaml
queue:
  tick-interval-sec: 1
  admits-per-tick: 100
```

`application-test.yml`에는 테스트 결정성 위해 작은 값:

```yaml
queue:
  tick-interval-sec: 1
  admits-per-tick: 100
```

(테스트는 `@TestPropertySource`로 case별 override)

### 4. ErrorCode 항목 추가

`global/response/ErrorCode.java`에:

```
QUEUE_NOT_ENABLED(HttpStatus.BAD_REQUEST, "QUEUE_NOT_ENABLED", "이 공연은 대기열을 사용하지 않거나 입장 가능 시간이 아닙니다"),
NOT_IN_QUEUE(HttpStatus.NOT_FOUND, "NOT_IN_QUEUE", "대기열에 등록되어 있지 않습니다"),
```

> step 2에서 `NOT_ADMITTED(403)` 추가 예정.

### 5. ConcertRepository 메서드 추가

`concert/repository/ConcertRepository.java`에:

```java
List<Concert> findByQueueEnabledTrue();
```

> step 1의 워커가 사용. 본 step에서는 단일 조회만 사용하므로 추가만 해두고 활용은 step 1.

### 6. DTO

`src/main/java/com/harness/ticket/queue/dto/QueueStatusResponse.java`:

```java
public record QueueStatusResponse(
    int position,        // 0-based rank (앞에 있는 사람 수)
    long etaSec,         // 예상 입장까지 초
    boolean admitted     // admit Set에 들어있으면 true (즉시 입장 가능)
) {}
```

### 7. QueueService

`src/main/java/com/harness/ticket/queue/service/QueueService.java`:

요구사항:
- `@Service @RequiredArgsConstructor @Slf4j`.
- 주입: `ConcertRepository`, `StringRedisTemplate`, `Clock`, `QueueProperties`.
- 메서드:

```java
public QueueStatusResponse enter(Long showId, Long userId) {
    Concert concert = concertRepository.findById(showId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));

    Instant now = Instant.now(clock);
    if (!concert.isQueueEnabled() || !now.isBefore(concert.getStartsAt().plus(Duration.ofHours(1)))) {
        // queueEnabled=false 또는 (공연 시작+1시간) 이후 = 만료
        throw new BusinessException(ErrorCode.QUEUE_NOT_ENABLED);
    }

    String queueKey = RedisKeys.queue(showId);
    long score = now.toEpochMilli();
    redisTemplate.opsForZSet().addIfAbsent(queueKey, userId.toString(), score);

    // queue 키 TTL: 공연 시작 + 1시간까지 (ADR-005)
    long ttlSec = Duration.between(now, concert.getStartsAt().plus(Duration.ofHours(1))).getSeconds();
    if (ttlSec > 0) {
        redisTemplate.expire(queueKey, Duration.ofSeconds(ttlSec));
    }

    return buildStatus(showId, userId);
}

public QueueStatusResponse getMyStatus(Long showId, Long userId) {
    Concert concert = concertRepository.findById(showId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));
    if (!concert.isQueueEnabled()) {
        throw new BusinessException(ErrorCode.QUEUE_NOT_ENABLED);
    }
    return buildStatus(showId, userId);
}

private QueueStatusResponse buildStatus(Long showId, Long userId) {
    String admitKey = RedisKeys.admit(showId);
    Boolean admitted = redisTemplate.opsForSet().isMember(admitKey, userId.toString());
    if (Boolean.TRUE.equals(admitted)) {
        return new QueueStatusResponse(0, 0L, true);
    }

    String queueKey = RedisKeys.queue(showId);
    Long rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString());
    if (rank == null) {
        throw new BusinessException(ErrorCode.NOT_IN_QUEUE);
    }

    long etaSec = computeEta((int) rank.longValue());
    return new QueueStatusResponse((int) rank.longValue(), etaSec, false);
}

private long computeEta(int rank) {
    int admitsPerTick = props.admitsPerTick();
    int tickIntervalSec = props.tickIntervalSec();
    // ceil((rank+1) / admitsPerTick) * tickIntervalSec
    long ticksAhead = ((long) rank / admitsPerTick) + 1;
    return ticksAhead * tickIntervalSec;
}
```

CRITICAL 규칙:
- `addIfAbsent` (= `ZADD NX`)로 중복 진입 방지. 같은 사용자 재호출해도 score는 첫 진입 timestamp 유지.
- queue 키 TTL은 매 enter 호출마다 재계산해 갱신 (인기 공연 큐가 자정 넘어 잘못 만료되는 사고 방지).
- `Instant.now(clock)` — `Clock` 주입 필수. `Instant.now()` 직접 호출 금지.
- ETA 계산 공식은 ADR-005 그대로. 정수 연산.

### 8. QueueController

`src/main/java/com/harness/ticket/queue/controller/QueueController.java`:

```java
@RestController @RequestMapping("/queue") @RequiredArgsConstructor
public class QueueController {
    private final QueueService queueService;

    @PostMapping("/{showId}/enter")
    public ApiResponse<QueueStatusResponse> enter(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long showId) {
        return ApiResponse.success(queueService.enter(showId, userId), "대기열에 입장했습니다");
    }

    @GetMapping("/{showId}/me")
    public ApiResponse<QueueStatusResponse> me(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long showId) {
        return ApiResponse.success(queueService.getMyStatus(showId, userId));
    }
}
```

### 9. SecurityConfig는 변경 불필요

기존 `anyRequest().authenticated()` 정책으로 `/queue/**`는 자동 보호됨.

### 10. 테스트

#### `queue/service/QueueServiceTest` (Mockito 단위)
- enter: queueEnabled=true 공연 → ZADD NX 호출, expire 호출, status 반환.
- enter: queueEnabled=false → `QUEUE_NOT_ENABLED`.
- enter: 공연 시작+1시간 지남 (Clock fixed로 시뮬레이션) → `QUEUE_NOT_ENABLED`.
- enter: 같은 사용자 재호출 → ZADD NX 두 번째는 update 없음, 응답 동일.
- getMyStatus: admit Set에 있음 → `(0, 0, true)`.
- getMyStatus: queue에만 있음 (rank=5, admits-per-tick=100, tick=1) → `(5, 1, false)`.
- getMyStatus: rank=99 → etaSec=1; rank=100 → etaSec=2 (경계 검증).
- getMyStatus: 큐에도 admit에도 없음 → `NOT_IN_QUEUE`.
- getMyStatus: queueEnabled=false → `QUEUE_NOT_ENABLED`.

#### `queue/controller/QueueIT` (Testcontainers 통합)
사전: signup 2명 → 각자 access 토큰 획득. seed 공연 id=2 (queueEnabled=true) 활용.

시나리오:
1. **queueEnabled=false 공연(id=1)에 enter** → 400 `QUEUE_NOT_ENABLED`.
2. **첫 사용자 enter** → 200, position=0, etaSec=1, admitted=false.
3. **첫 사용자 재호출 enter** → 200, position 동일 (0). Redis ZRANK 검증.
4. **두 번째 사용자 enter** → 200, position=1, etaSec=1.
5. **첫 사용자 GET /me** → position=0, etaSec=1, admitted=false.
6. **큐 미등록 사용자 GET /me** → 404 `NOT_IN_QUEUE`. (signup만 한 별개 사용자로 시뮬레이션)
7. **존재하지 않는 showId** → 404 NOT_FOUND.
8. **토큰 없이 enter/me** → 401 UNAUTHORIZED.
9. **queue Redis TTL 검증** — enter 후 `redisTemplate.getExpire(queue:2, SECONDS)` 가 양수, 그리고 공연 시작+1시간 근처 값.

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "com.harness.ticket.queue.*"
```

위 명령들이 성공해야 한다. 이전 phase 모든 테스트도 깨지지 않아야 한다.

## 검증 절차

1. 위 AC 커맨드 실행.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` "대기열 시퀀스"의 enter / me 동작 일치.
   - `/docs/ADR.md` ADR-005 — Sorted Set + ZADD NX + ZRANK + ETA 공식 + queue TTL=공연 시작+1시간.
   - `/CLAUDE.md` CRITICAL — Clock 주입, Redis 키 헬퍼, DTO 응답.
3. `phases/4-queue/index.json`의 step 0을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "QueueProperties + V6 seed(queueEnabled=true 공연 id=2) + QueueService(ZADD NX·ZRANK·ETA·공연 시작+1시간 TTL) + POST /queue/{showId}/enter + GET /queue/{showId}/me + ErrorCode 2종(QUEUE_NOT_ENABLED·NOT_IN_QUEUE) + 통합 9 시나리오"`
   - 실패/중단 시 error/blocked.

## 금지사항

- `AdmitWorker`, admit Set 채우는 로직, `@Scheduled` 워커 만들지 마라. 이유: step 1에서 일관 추가.
- `ReservationService.reserve` 수정 금지 (admit 검사 추가는 step 2). 이유: step 분리.
- `ZADD` 단순 사용 금지. 반드시 `addIfAbsent`(=ZADD NX). 같은 사용자 재호출 시 score가 갱신되면 FIFO 순서 깨진다.
- `Instant.now()` 직접 호출 금지 — Clock 주입.
- queue Redis 키 TTL 누락 금지. 인기 없는 공연에서 큐가 영구 잔존하면 메모리 누수.
- ETA 공식 임의 변경 금지 — ADR-005 공식 그대로 (`ceil((rank+1)/admitsPerTick) * tickIntervalSec`).
- `/queue/**` 경로를 permitAll에 추가하지 마라. 인증 필수.
- userId path/body 수신 금지. `@AuthenticationPrincipal`.
- `Concert` JPA 매핑(`@OneToMany Reservation` 등) 추가 금지. 단일 ID 필드 컨벤션 유지.
- 기존 phase 1~3 테스트 깨뜨리지 마라.
