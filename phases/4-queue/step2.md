# Step 2: reservation-gate

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙. Redis 키 헬퍼, DTO.
- `/docs/ARCHITECTURE.md` — **"좌석 선점 시퀀스"의 첫 단계**: `Concert.queueEnabled=true` 일 때 `SISMEMBER admit:{showId}` 검사.
- `/docs/ADR.md` — ADR-002 좌석 선점 절차, ADR-005 admit 검증.
- 이전 step과 phase 산출물:
  - `reservation/service/ReservationService.java` — `reserve` 메서드. phase 3-step 1에서 남긴 주석 자리에 admit 검사 추가
  - `reservation/controller/ReservationController.java` — POST /reservations endpoint
  - `concert/domain/Concert.java` — `queueEnabled` 필드
  - `global/redis/RedisKeys.java` — `admit(showId)`
  - `queue/service/QueueService.java`, `queue/scheduler/AdmitWorker.java` — 이전 step에서 만들어진 큐 인프라
  - `src/main/resources/db/migration/V6__seed_queued_concert.sql` — queueEnabled=true 공연 id=2 seed

phase 3-step 1의 `ReservationService.reserve` 코드를 정확히 읽고, **주석 자리에만** 추가 로직을 넣어라. 다른 부분은 건드리지 마라 (보상 트랜잭션, 카운터, SET NX 등 그대로).

## 작업

좌석 선점 시 `queueEnabled=true` 공연이면 admit Set 검사 추가 + queue→admit→reserve 통합 시나리오 검증.

### 1. ErrorCode 추가

`global/response/ErrorCode.java`에:

```
NOT_ADMITTED(HttpStatus.FORBIDDEN, "NOT_ADMITTED", "대기열에서 입장 허가를 받지 못했습니다"),
```

### 2. ReservationService.reserve 수정

phase 3-step 1에서 작성된 코드 중 주석 부분을 실제 로직으로 교체:

```java
// 1. queueEnabled true이면 phase 4에서 admit 검사 추가 예정 — 현 step에선 통과
Concert concert = concertRepository.findById(concertId)
    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));
// (queueEnabled 체크 — phase 4-queue에서 SISMEMBER admit:{showId} userId 검사 추가)
```

→

```java
Concert concert = concertRepository.findById(concertId)
    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));

if (concert.isQueueEnabled()) {
    Boolean admitted = redisTemplate.opsForSet()
        .isMember(RedisKeys.admit(concertId), userId.toString());
    if (!Boolean.TRUE.equals(admitted)) {
        throw new BusinessException(ErrorCode.NOT_ADMITTED);
    }
}
```

CRITICAL 규칙:
- 이 검사는 **count 카운터 / SET NX / DB insert 모두 이전에 위치**. 즉 admit 없는 사용자가 카운터를 증가시키거나 좌석 점유하지 않게.
- queueEnabled=false 공연(phase 3 seed concert id=1)은 검사 우회 → 기존 phase 3 동작 100% 유지.

### 3. 테스트

#### `reservation/service/ReservationServiceQueueGateTest` (Mockito 단위)
- queueEnabled=true + admit Set에 없음 → `NOT_ADMITTED`. count·SET NX·save 호출되지 않음 검증.
- queueEnabled=true + admit Set에 있음 → 정상 흐름 (count 증가, SET NX, save).
- queueEnabled=false → admit 검사 skip, 정상 흐름.

#### `reservation/controller/ReservationQueueGateIT` (Testcontainers 통합)
사전: signup 1명 + access 토큰 + seed concert id=2 (queueEnabled=true) 활용.

**`@TestPropertySource(properties = "spring.task.scheduling.pool.size=0")` 또는 `runOnce()` 직접 호출 패턴 사용**. 시간 의존 제거.

시나리오:
1. **queueEnabled=true 공연에 admit 없이 reserve 시도**: `POST /reservations { concertId:2, seatId: 100 }` (concert 2의 첫 좌석 id가 100~199 범위라 가정 — 실제 seed에선 좌석 id 자동 생성이라 테스트 시 `SeatRepository.findByConcertId(2L, ...)`로 동적 조회)
   → 403 `NOT_ADMITTED`. DB Reservation 없음, Redis count·seat 키 없음.
2. **queue enter → 워커 → reserve**:
   - `POST /queue/2/enter` → 200, position=0
   - `worker.runOnce()` 직접 호출 → admit Set에 진입
   - `POST /reservations { concertId:2, seatId:<x> }` → 201 PENDING
3. **queue enter했지만 admit 받기 전 reserve 시도**:
   - `POST /queue/2/enter` → 200 (admit Set엔 아직 없음)
   - 워커 호출 안 함
   - `POST /reservations` → 403 `NOT_ADMITTED`
4. **admit 후 reserve 정상 + 결제 완료된 좌석을 다른 사용자가 admit + reserve 시도**:
   - 사용자 A: enter → admit → reserve → pay (success)
   - 사용자 B: enter → admit → 같은 좌석 reserve → 409 `SEAT_ALREADY_HELD` (admit은 통과지만 Redis seat 키 없어진 상태에서 SET NX 실패 → 잠깐, **PAID 시 Redis 키 삭제됨**. 다음 사용자가 SET NX 시도하면 성공? 응 검증 필요)
   - 정확히는: PAID 후 Redis seat 키 삭제 → 다른 사용자가 같은 좌석 SET NX 성공 가능. **하지만 그 좌석의 Reservation row가 PAID로 이미 존재**. 좌석 id 단위의 unique 제약이 없으므로 새 Reservation row(PENDING)가 만들어진다.
   - 이 시나리오는 phase 3 설계상 "결제 완료 좌석에 새 reservation 생성 가능"이지만, 사실상 동시 발생 거의 없고 부하 테스트에서 다룬다. **본 IT에서는 PAID 후 같은 좌석 재예매 시나리오 대신 다른 좌석 시도로 단순화 권장**:
     - 사용자 B: enter → admit → 다른 좌석 reserve → 201 PENDING
5. **queueEnabled=false 공연(id=1) 회귀 테스트** — phase 3 시나리오 1개 재실행:
   - admit 없이 `POST /reservations { concertId:1, seatId:<x> }` → 201 PENDING (admit 검사 우회).
6. **잘못된 concertId**: 999999 → 404 `NOT_FOUND`.
7. **토큰 없이 reserve** → 401 `UNAUTHORIZED`.

> seed의 좌석 id 동적 조회: `seatRepository.findByConcertId(2L, PageRequest.of(0,1)).getContent().get(0).getId()` 사용.

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "*ReservationServiceQueueGate*" --tests "*ReservationQueueGate*"
```

위 명령들이 성공해야 한다. **phase 3의 모든 동시성·결제·취소·만료 테스트도 그대로 통과**해야 한다 (queueEnabled=false 공연은 admit 검사 우회).

## 검증 절차

1. 위 AC 커맨드 실행. **`ReservationConcurrencyIT` (phase 3-step 1)와 phase 3 모든 IT가 깨지지 않아야 한다** — 회귀 검증 필수.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` "좌석 선점 시퀀스" 첫 단계 SISMEMBER 검사 구현됨.
   - `/CLAUDE.md` CRITICAL — Redis 키 헬퍼 사용, DTO 응답.
3. `phases/4-queue/index.json`의 step 2를 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "ReservationService.reserve에 queueEnabled=true 공연 SISMEMBER admit 게이트 추가 + ErrorCode NOT_ADMITTED + 통합 IT(admit 없이 거부·queue→admit→reserve 정상·queueEnabled=false 우회 회귀)"`
   - 실패/중단 시 error/blocked.
4. **phase 4 전체 완료** — index.json `status: "completed"` 자동 마킹.

## 금지사항

- admit 검사를 count·SET NX 이후에 두지 마라. 반드시 가장 앞 (Concert 조회 직후). 이유: admit 없는 사용자가 카운터·좌석 키를 잠시라도 점유하면 안 됨.
- queueEnabled=false 공연에서 admit 검사 적용 금지. 이유: phase 3 동작 회귀 불가.
- `ReservationService.reserve`의 다른 로직(count, SET NX, save, 보상) 변경 금지. 본 step은 admit 게이트 추가만.
- `NOT_ADMITTED` HTTP 상태를 401·404 등으로 바꾸지 마라. **403** 정확히. 이유: 인증은 됐으나 admit 권한이 없는 상태.
- 테스트에서 `Thread.sleep`으로 워커를 기다리지 마라. `worker.runOnce()` 직접 호출.
- 시나리오 4의 PAID 후 좌석 재예매를 깊이 검증하지 마라 (phase 3·5 영역). 본 step은 admit 게이트 동작에 집중.
- queue 자체 로직 (enter/me/worker) 변경 금지. step 0/1에서 완성.
- 기존 phase 1~3 + step 0/1 테스트 깨뜨리지 마라.
