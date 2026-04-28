# Step 1: admit-worker

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙. 특히 Clock 주입, Redis 키 헬퍼.
- `/docs/ARCHITECTURE.md` — **"대기열 시퀀스"의 워커 부분**.
- `/docs/ADR.md` — **ADR-005 워커 정책 (admits-per-tick, tick-interval-sec, 비원자성, admit Set TTL=10분)**.
- 이전 step과 phase 산출물:
  - `queue/service/QueueService.java` — `getMyStatus`가 admit Set을 SISMEMBER로 확인 (이미 step 0에서 구현)
  - `concert/repository/ConcertRepository.java` — `findByQueueEnabledTrue()` 메서드 이미 추가됨 (step 0)
  - `global/redis/RedisKeys.java` — `queue(showId)`, `admit(showId)` 메서드
  - `global/config/QueueProperties.java`
  - `reservation/scheduler/PendingReservationCleaner.java` — `@Scheduled` 패턴 참조 (Clock 주입 + @Transactional 패턴)
  - `application.yml` — `queue.tick-interval-sec`, `queue.admits-per-tick` 정의됨

`PendingReservationCleaner`의 `@Scheduled` 패턴을 정확히 참조하되, **본 워커는 DB write가 없으므로 `@Transactional` 불필요** — Redis 작업만 한다.

## 작업

`AdmitWorker` 스케줄러 — queue Sorted Set 상위 N명을 admit Set으로 이동.

### 1. AdmitWorker

`src/main/java/com/harness/ticket/queue/scheduler/AdmitWorker.java`:

```java
@Component @RequiredArgsConstructor @Slf4j
public class AdmitWorker {

    private static final Duration ADMIT_TTL = Duration.ofMinutes(10);

    private final ConcertRepository concertRepository;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;

    @Scheduled(fixedDelayString = "#{${queue.tick-interval-sec} * 1000}")
    public void tick() {
        runOnce();
    }

    /** Public for direct invocation in integration tests (no @Scheduled timing dependency). */
    public void runOnce() {
        List<Concert> active = concertRepository.findByQueueEnabledTrue();
        for (Concert c : active) {
            try {
                admitFor(c.getId());
            } catch (Exception e) {
                log.error("admit failed showId={}", c.getId(), e);
            }
        }
    }

    private void admitFor(Long showId) {
        String queueKey = RedisKeys.queue(showId);
        String admitKey = RedisKeys.admit(showId);

        Set<ZSetOperations.TypedTuple<String>> popped =
            redisTemplate.opsForZSet().popMin(queueKey, props.admitsPerTick());
        if (popped == null || popped.isEmpty()) return;

        for (ZSetOperations.TypedTuple<String> t : popped) {
            String userId = t.getValue();
            if (userId == null) continue;
            redisTemplate.opsForSet().add(admitKey, userId);
        }
        // admit Set TTL 갱신 (사용자 추가될 때마다)
        redisTemplate.expire(admitKey, ADMIT_TTL);
        log.info("admitted showId={} count={}", showId, popped.size());
    }
}
```

CRITICAL 규칙:
- **워커 비원자성 인지** (ADR-005): `popMin` 후 `add` 사이에 워커가 죽으면 사용자 유실. MVP 정책상 사용자 재진입 허용으로 보강. Lua 스크립트 원자화는 다음 마일스톤.
- 공연 단위 `try/catch`: 한 공연의 admit이 실패해도 다른 공연은 계속 처리. 워커 자체가 죽지 않게.
- `runOnce()`는 public — 테스트에서 `@Scheduled` 의존 없이 직접 호출.
- `@Transactional` 사용 금지 — DB write 없음. Redis만.
- `Clock` 주입 불필요 (시간 비교 로직 없음 — pop과 add만).

### 2. 테스트

#### `queue/scheduler/AdmitWorkerTest` (Mockito 단위)
- queueEnabled=true 공연 1개 + queue에 사용자 5명 + admits-per-tick=100 → `runOnce()` 호출 → ZSet popMin 호출, SADD 5번, expire 호출.
- queueEnabled=true 공연 2개 + 둘 다 큐에 사용자 → 둘 다 admit 처리.
- queue 비어있음 (popMin 결과 empty) → SADD/expire 호출 안 됨.
- 한 공연에서 예외 발생 (popMin throw) → 다른 공연은 계속 처리됨, 워커 자체는 정상 종료.

#### `queue/scheduler/AdmitWorkerIT` (Testcontainers 통합)
**`@Scheduled` 자동 실행 비활성화**: 다음 중 하나로 처리:
- (a) `@TestPropertySource(properties = "spring.task.scheduling.pool.size=0")` — 스케줄러 풀 자체 disable
- (b) `@MockBean PendingReservationCleaner` 등으로 다른 스케줄러 모킹 (필요 시)
- (c) 본 테스트에서 시간 의존을 명시적으로 제거: `@SpringBootTest` 후 `worker.runOnce()` 직접 호출

권장: (c) — `runOnce()` 직접 호출이 가장 결정적.

시나리오:
1. **정상 admit**: signup 5명 + 모두 `POST /queue/2/enter` (queueEnabled=true 공연) → `worker.runOnce()` 1회 호출 → 모두 admit Set 진입, queue Sorted Set 비어있음 검증.
2. **admits-per-tick 제한** (`@TestPropertySource` 또는 `@DynamicPropertySource`로 admits-per-tick=2로 override):
   - 5명 enter → `runOnce()` 1회 → 2명 admit, 3명 queue 잔존
   - `runOnce()` 2회 → 추가 2명 admit (총 4명), 1명 queue
   - `runOnce()` 3회 → 마지막 1명 admit, queue 비어있음
3. **queueEnabled=false 공연 무시**: 공연 id=1 (phase 3 seed, queueEnabled=false)에 직접 ZADD로 큐 데이터 주입(테스트 셋업) → `runOnce()` 호출 → queue 그대로 (워커가 무시), admit Set 비어있음.
4. **admit Set TTL 검증**: 5명 admit 후 `redisTemplate.getExpire(admit:2, SECONDS)` → 600초 근처 값.
5. **워커 비원자성 시나리오** (선택, 명문화 목적):
   - Mockito spy로 `redisTemplate.opsForSet().add()` 호출 시 1번째에 `RuntimeException` throw
   - `runOnce()` 호출 → 첫 사용자는 ZPOPMIN으로 큐에서 빠지지만 admit Set에 추가 안 됨 (= 유실)
   - 워커는 catch로 에러 로그 후 다른 공연 계속 처리. 사용자가 다시 enter 가능 — 검증.

> 위 시나리오 5는 구현 난이도가 있고 spy 셋업이 까다로우면 skip 가능. 핵심은 1~4.

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "*AdmitWorker*"
```

위 명령들이 성공해야 한다. 이전 step의 `QueueService` / `QueueIT` 테스트도 깨지지 않아야 한다 (step 0의 admit 분기는 admit Set 비어있을 때의 동작이 그대로 유효).

## 검증 절차

1. 위 AC 커맨드 실행.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` 워커 시퀀스 일치.
   - `/docs/ADR.md` ADR-005 — popMin admits-per-tick + SADD admit + EXPIRE 600 + 비원자성 명시.
   - `/CLAUDE.md` CRITICAL — Redis 키 헬퍼.
3. `phases/4-queue/index.json`의 step 1을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "AdmitWorker @Scheduled fixedDelayString(tick-interval-sec*1000ms) + runOnce(public, public for IT) + ZPOPMIN admits-per-tick → SADD admit:{showId} + admit TTL 600s + 공연별 try/catch + 비원자성 명시. 통합 IT(정상·tick 제한·flag 무시·TTL 검증)"`
   - 실패/중단 시 error/blocked.

## 금지사항

- 워커에서 `@Transactional` 사용 금지. 이유: DB write 없음. 트랜잭션은 불필요한 오버헤드 + 의도 모호화.
- 워커의 fixedDelay 값을 임의 변경 금지. 반드시 `queue.tick-interval-sec`을 SpEL로 참조.
- `popMin` + `add`를 Lua 스크립트로 원자화하려 시도하지 마라. 이유: ADR-005 — MVP 정책은 비원자성 허용 + 사용자 재진입. 원자화는 다음 마일스톤.
- 워커가 한 공연의 예외로 전체 종료되지 않게 try/catch 누락 금지.
- 테스트에서 `Thread.sleep`으로 워커 자동 실행을 기다리지 마라. flaky. 반드시 `worker.runOnce()` 직접 호출.
- admit Set TTL 누락 금지. 600초 (= 좌석 선점 TTL과 동일).
- queueEnabled=false 공연을 처리하지 마라. `findByQueueEnabledTrue` 사용.
- `ReservationService.reserve` 수정 금지 (step 2). admit 검사 추가는 step 2.
- 기존 phase 1~3 테스트 + step 0 테스트를 깨뜨리지 마라.
