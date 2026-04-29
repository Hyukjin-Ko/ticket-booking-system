# Step 1: scenarios

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — PII 로깅 금지 (k6 스크립트도 동일).
- `/docs/PRD.md` — 1000 TPS 목표, 좌석 중복 예매 0건 보장.
- `/docs/ADR.md` — ADR-002 좌석 선점, ADR-005 대기열 (admit 정책).
- 이전 step 산출물:
  - `loadtest/lib/auth.js` — `randomUsername`, `signupAndLogin`
  - `loadtest/lib/setup.js` — `fetchConcerts`, `fetchSeats`
  - `loadtest/lib/config.js` — `BASE_URL`, `bearer`, `COMMON_THRESHOLDS`
  - `loadtest/smoke.js` — 시나리오 작성 패턴 참조
- 백엔드 endpoint:
  - 인증: `POST /auth/signup`, `/auth/login`
  - 좌석 정보: `GET /concerts`, `/concerts/{id}/seats`
  - 예매: `POST /reservations`, `/reservations/{id}/pay` (헤더 `X-Mock-Pay-Result`), `DELETE /reservations/{id}`
  - 대기열: `POST /queue/{showId}/enter`, `GET /queue/{showId}/me`

각 endpoint의 정확한 request/response 형식을 파악하고 그에 맞는 시나리오를 작성하라.

## 작업

핵심 시나리오 4개. 측정은 step 2에서 (본 step은 작성·컴파일만).

### 시나리오 1: `loadtest/scenario-seat-contention.js` — 정확성 검증

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, bearer, JSON_HEADERS } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';

const successCount = new Counter('seat_reserve_success');
const conflictCount = new Counter('seat_reserve_conflict');

export const options = {
  scenarios: {
    contention: {
      executor: 'per-vu-iterations',
      vus: 1000,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    seat_reserve_success:  ['count==1'],     // ★ 정확히 1
    seat_reserve_conflict: ['count==999'],   // ★ 정확히 999
    http_req_failed:       ['rate<0.01'],
  },
};

const TARGET_CONCERT_ID = 1;   // queueEnabled=false (admit 검사 우회)
const TARGET_SEAT_ID    = 1;

export default function () {
  const username = randomUsername('contend');
  const token = signupAndLogin(username);
  if (!token) return;

  const res = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ concertId: TARGET_CONCERT_ID, seatId: TARGET_SEAT_ID }),
    { headers: bearer(token), tags: { name: 'reserve_contended' } }
  );

  if (res.status === 201) {
    successCount.add(1);
  } else if (res.status === 409) {
    conflictCount.add(1);
  }
  check(res, {
    'reserve 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}
```

> **본 시나리오는 부하 측정이 아닌 정확성(correctness) 검증**. 1000 사용자 모두 동일 좌석 시도 → 정확히 1명만 성공. 부하 테스트지만 본질은 동시성 정확성.

### 시나리오 2: `loadtest/scenario-distributed.js` — throughput

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, bearer, JSON_HEADERS } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';
import { fetchSeats } from './lib/setup.js';

export const options = {
  scenarios: {
    distributed: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 500 },   // ramp up
        { duration: '1m',  target: 1000 },  // sustain 1000 VU
        { duration: '30s', target: 0 },     // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed:       ['rate<0.05'],
    http_req_duration:     ['p(95)<500'],
    'http_req_duration{name:reserve_distributed}': ['p(95)<500'],
  },
};

const TARGET_CONCERT_ID = 1;

export default function () {
  const username = randomUsername('dist');
  const token = signupAndLogin(username);
  if (!token) return;

  // 좌석 풀에서 랜덤 선택 (1~100)
  const seatId = Math.floor(Math.random() * 100) + 1;

  const res = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ concertId: TARGET_CONCERT_ID, seatId }),
    { headers: bearer(token), tags: { name: 'reserve_distributed' } }
  );
  check(res, {
    'reserve 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
  sleep(0.1);
}
```

> 1000 VU가 분산된 좌석에 시도. 성공률·p95 latency가 핵심 지표.

### 시나리오 3: `loadtest/scenario-payment-flow.js` — end-to-end

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, bearer, JSON_HEADERS } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';

export const options = {
  scenarios: {
    payment: {
      executor: 'constant-vus',
      vus: 100,
      duration: '2m',
    },
  },
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(95)<5000'],   // 전체 플로우 5초 이하
  },
};

const TARGET_CONCERT_ID = 1;   // queueEnabled=false (큐 우회 - 단순 플로우)

export default function () {
  const username = randomUsername('pay');
  const token = signupAndLogin(username);
  if (!token) return;

  const seatId = Math.floor(Math.random() * 100) + 1;

  // reserve
  const rRes = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ concertId: TARGET_CONCERT_ID, seatId }),
    { headers: bearer(token), tags: { name: 'reserve' } }
  );
  if (rRes.status !== 201) { sleep(0.5); return; }
  const reservationId = rRes.json().data.id;

  // pay (deterministic success)
  const pHeaders = { ...bearer(token), 'X-Mock-Pay-Result': 'success' };
  const pRes = http.post(
    `${BASE_URL}/reservations/${reservationId}/pay`,
    null,
    { headers: pHeaders, tags: { name: 'pay' } }
  );
  check(pRes, { 'pay 200': (r) => r.status === 200 });

  sleep(0.5);
}
```

> 전체 플로우 (signup → login → reserve → pay) 의 latency 분포 측정.

### 시나리오 4: `loadtest/scenario-queue-burst.js` — 대기열

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, bearer } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';

export const options = {
  scenarios: {
    queue_burst: {
      executor: 'per-vu-iterations',
      vus: 5000,
      iterations: 1,
      maxDuration: '120s',
    },
  },
  thresholds: {
    http_req_failed:       ['rate<0.01'],
    'http_req_duration{name:queue_enter}': ['p(95)<500'],
  },
};

const QUEUED_CONCERT_ID = 2;   // queueEnabled=true

export default function () {
  const username = randomUsername('qburst');
  const token = signupAndLogin(username);
  if (!token) return;

  const res = http.post(
    `${BASE_URL}/queue/${QUEUED_CONCERT_ID}/enter`,
    null,
    { headers: bearer(token), tags: { name: 'queue_enter' } }
  );
  check(res, { 'enter 200': (r) => r.status === 200 });
}
```

> 5000 VU 동시 큐 입장. ZADD NX 동시성 + queue 키 TTL 설정 동작 확인. throughput 검증.

## Acceptance Criteria

```bash
# 4개 시나리오 컴파일 검증 (k6 archive)
k6 archive loadtest/scenario-seat-contention.js -O /tmp/x1.tar
k6 archive loadtest/scenario-distributed.js     -O /tmp/x2.tar
k6 archive loadtest/scenario-payment-flow.js    -O /tmp/x3.tar
k6 archive loadtest/scenario-queue-burst.js     -O /tmp/x4.tar
```

> 실제 1000/5000 VU 측정은 step 2 + 사용자 수동 진행.

## 검증 절차

1. 위 AC 커맨드 실행 (k6 archive로 ES module 문법 + import 경로 검증).
2. 산출물 체크:
   - 4개 파일 모두 `import` 경로가 `./lib/...` 정확.
   - `options.thresholds`가 시나리오별로 의미 있게 정의됨.
   - `signupAndLogin` 사용 — 직접 http.post 중복 작성 금지.
   - 비밀번호·토큰 console.log 없음.
3. `phases/5-loadtest/index.json`의 step 1을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "4개 시나리오 작성 - seat-contention(1000VU 정확성)·distributed(ramping 1000VU throughput)·payment-flow(100VU 2m e2e)·queue-burst(5000VU). k6 archive 4개 모두 통과"`
   - k6 archive 실패 → blocked.

## 금지사항

- 시나리오 파일에서 임의의 백엔드 endpoint 추가 호출 금지. **본 phase는 측정용** — 새 endpoint 필요하면 별도 phase에서 백엔드 구현 후.
- helper(`signupAndLogin`, `fetchSeats`)를 우회한 직접 http.post/get 금지 (특정 시나리오 의도가 helper와 다른 경우만 예외).
- `console.log`로 비밀번호·토큰 출력 금지.
- `Math.random()`을 좌석 분산이 아닌 다른 동시성 처리에 사용 금지 (k6 시드 비결정성).
- 시나리오에서 `__ENV.BASE_URL` 무시하고 하드코딩 금지.
- 백엔드 코드 변경 금지.
- 시나리오 5번째 추가 금지 — 4개로 충분.
- step 2의 virtual thread 관련 코드(application.yml, BENCHMARK.md) 작성 금지.
- 기존 phase 1~4 테스트 깨뜨리지 마라.
