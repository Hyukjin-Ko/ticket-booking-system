# Step 0: loadtest-infra

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — 프로젝트 규칙. 본 step은 백엔드 코드 변경 없으나 컨벤션은 인지.
- `/docs/PRD.md` — 핵심 기능 7번 (k6 부하 테스트 + before/after 리포트).
- `/docs/ARCHITECTURE.md` — `loadtest/` 디렉토리 구조.
- 이전 phase에서 만든 endpoint들:
  - `auth/controller/AuthController.java` — POST /auth/signup, /auth/login
  - `concert/controller/ConcertController.java` — GET /concerts, /concerts/{id}/seats
  - `reservation/controller/ReservationController.java` — POST /reservations, /reservations/{id}/pay, DELETE /reservations/{id}
  - `queue/controller/QueueController.java` — POST /queue/{showId}/enter, GET /queue/{showId}/me

각 endpoint의 request/response 형식을 정확히 파악한 뒤 k6 helper를 작성하라. 응답은 모두 `{success, code, message, data}` 형식.

## 작업

k6 인프라 (디렉토리 + helper 라이브러리 + smoke test).

### 1. 디렉토리 구조

루트에 `loadtest/` 생성. 내부 구조:

```
loadtest/
├── README.md
├── lib/
│   ├── auth.js
│   ├── setup.js
│   └── config.js
├── smoke.js
└── results/        # gitignored (측정 결과 저장)
    └── .gitkeep    # 디렉토리 보존용
```

### 2. .gitignore 갱신

기존 `.gitignore`에 추가:

```
# k6 측정 결과
loadtest/results/
!loadtest/results/.gitkeep
```

> `.gitkeep`은 명시적으로 추적해 디렉토리 자체는 유지.

### 3. `loadtest/lib/config.js`

```javascript
// 공통 설정 — env var로 base URL 주입 가능
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 표준 thresholds (시나리오별로 override 가능)
export const COMMON_THRESHOLDS = {
  http_req_failed:   ['rate<0.01'],     // 에러율 1% 미만
  http_req_duration: ['p(95)<1000'],    // p95 < 1초
};

// 공통 헤더
export const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function bearer(token) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}
```

### 4. `loadtest/lib/auth.js`

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, JSON_HEADERS } from './config.js';

/** 고유 username 생성 (VU+iteration 기반) */
export function randomUsername(prefix = 'k6user') {
  return `${prefix}${__VU}_${__ITER}_${Date.now() % 100000}`;
}

/** signup → login. access 토큰 반환. */
export function signupAndLogin(username, password = 'k6password1') {
  // signup
  const sRes = http.post(
    `${BASE_URL}/auth/signup`,
    JSON.stringify({ username, password }),
    { headers: JSON_HEADERS, tags: { name: 'signup' } }
  );
  check(sRes, { 'signup 201': (r) => r.status === 201 });

  // login
  const lRes = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username, password }),
    { headers: JSON_HEADERS, tags: { name: 'login' } }
  );
  check(lRes, { 'login 200': (r) => r.status === 200 });

  const body = lRes.json();
  return body && body.data ? body.data.accessToken : null;
}
```

### 5. `loadtest/lib/setup.js`

```javascript
import http from 'k6/http';
import { BASE_URL, bearer } from './config.js';

/** 공연 목록 조회. 토큰 필요. */
export function fetchConcerts(token) {
  const r = http.get(`${BASE_URL}/concerts?size=20`, { headers: bearer(token) });
  return r.json().data.content;
}

/** 좌석 목록 조회. 토큰 필요. */
export function fetchSeats(token, concertId, page = 0, size = 100) {
  const r = http.get(
    `${BASE_URL}/concerts/${concertId}/seats?page=${page}&size=${size}`,
    { headers: bearer(token) }
  );
  return r.json().data.content;
}
```

### 6. `loadtest/smoke.js`

10 VU, 30초. 시스템 기본 동작 확인.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, bearer, COMMON_THRESHOLDS } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';
import { fetchConcerts, fetchSeats } from './lib/setup.js';

export const options = {
  vus: 10,
  duration: '30s',
  thresholds: COMMON_THRESHOLDS,
};

export default function () {
  const username = randomUsername('smoke');
  const token = signupAndLogin(username);
  check(token, { 'token issued': (t) => !!t });

  const concerts = fetchConcerts(token);
  check(concerts, { 'concerts >= 1': (c) => c.length >= 1 });

  if (concerts.length > 0) {
    const seats = fetchSeats(token, concerts[0].id);
    check(seats, { 'seats >= 1': (s) => s.length >= 1 });
  }

  sleep(1);
}
```

### 7. `loadtest/README.md`

```markdown
# Load Test (k6)

## 설치
\`\`\`bash
brew install k6
\`\`\`

## 실행 전 준비
\`\`\`bash
# 1. 인프라 + 앱 기동
docker-compose up -d
set -a && source .env && set +a
./gradlew bootRun &

# 2. 헬스체크 확인
curl -fsS http://localhost:8080/actuator/health
\`\`\`

## 시나리오

| 시나리오 | 파일 | 부하 | 목적 |
|---|---|---|---|
| Smoke | \`smoke.js\` | 10 VU 30s | 기본 동작 확인 |
| 좌석 경합 | \`scenario-seat-contention.js\` | 1000 VU | 정확성 (1성공·999실패) |
| 분산 예매 | \`scenario-distributed.js\` | 1000 VU | throughput |
| 전체 플로우 | \`scenario-payment-flow.js\` | 100 VU | end-to-end latency |
| 대기열 폭주 | \`scenario-queue-burst.js\` | 5000 VU | queue throughput |

## 실행
\`\`\`bash
# Smoke
k6 run loadtest/smoke.js

# 핵심 시나리오 (결과 JSON 저장)
k6 run --out json=loadtest/results/seat-contention.json loadtest/scenario-seat-contention.js

# Virtual Thread 비교
SPRING_THREADS_VIRTUAL_ENABLED=false ./gradlew bootRun &  # before
SPRING_THREADS_VIRTUAL_ENABLED=true  ./gradlew bootRun &  # after
\`\`\`

## 환경 변수
| 변수 | 기본값 | 설명 |
|---|---|---|
| BASE_URL | http://localhost:8080 | 대상 서버 |
\`\`\`

> 결과 파일은 \`loadtest/results/\` 에 저장 (gitignore).
```

### 8. `loadtest/results/.gitkeep`

빈 파일 생성 (디렉토리 보존용).

## Acceptance Criteria

```bash
# 1. 디렉토리/파일 존재
ls loadtest/lib/auth.js loadtest/lib/setup.js loadtest/lib/config.js
ls loadtest/smoke.js loadtest/README.md loadtest/results/.gitkeep

# 2. k6 시나리오 컴파일 (문법 검증)
k6 archive loadtest/smoke.js -O /tmp/smoke.tar
```

> k6 가 설치 안 된 환경에서는 `k6 archive` 가 실패한다. 그 경우 step을 blocked 처리하고 사용자가 `brew install k6` 후 재실행.

## 검증 절차

1. 위 AC 커맨드 실행.
2. 산출물 체크:
   - 모든 `.js` 파일이 ES module 형식 (`export`, `import` 사용).
   - `auth.js`의 `signupAndLogin`이 응답 body에서 `data.accessToken`을 정확히 추출 (응답 형식 `{success, code, message, data}`).
   - `setup.js`가 페이지네이션 응답 (`data.content`)을 정확히 처리.
3. `phases/5-loadtest/index.json`의 step 0을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "loadtest/ 디렉토리 + lib/(auth, setup, config) + smoke.js(10VU 30s) + README + .gitignore 갱신 + .gitkeep + k6 archive 검증 통과"`
   - k6 미설치로 archive 실패 → `"status": "blocked"`, `"blocked_reason": "k6 미설치. brew install k6 후 재실행"`. 코드 산출물은 정상.

## 금지사항

- 백엔드 코드(`src/main/java/`) 변경 금지. 본 phase는 부하 테스트 인프라만.
- k6 helper에서 비밀번호·토큰을 로그(`console.log`)에 출력 금지. PII 원칙 동일 적용.
- `BASE_URL`을 하드코딩 금지. 반드시 `__ENV.BASE_URL` 통과.
- `loadtest/results/` 디렉토리를 gitignore에 추가하지 않으면 측정 결과가 commit되어 repo가 부풀어오른다 — 반드시 gitignore + .gitkeep.
- 시나리오 파일 작성 금지 (step 1).
- 백엔드 application.yml 변경 금지 (step 2의 virtual thread 영역).
- 기존 phase 1~4 테스트 깨뜨리지 마라 (백엔드 변경 없으니 깨질 일 없음).
