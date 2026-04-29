# Step 2: virtual-thread-comparison

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — 본 step은 application.yml 1줄 추가 + 문서 작성. 코드 변경 거의 없음.
- `/docs/PRD.md` — phase 5 목표.
- `/docs/ADR.md` — ADR-001 (Java 21, virtual thread 언급).
- 이전 step 산출물:
  - `loadtest/scenario-distributed.js` — 측정 대상
  - `loadtest/scenario-payment-flow.js` — 측정 대상
  - `loadtest/scenario-seat-contention.js` — 회귀 (정확성 1성공/999실패)
  - `loadtest/scenario-queue-burst.js` — 측정 대상
  - `loadtest/README.md` — 실행 가이드
- 백엔드:
  - `src/main/resources/application.yml` — `spring.threads.virtual.enabled` 옵션 추가할 자리

## 작업

본 step은 두 단계로 진행된다:

- **자동 작업** (Claude 세션이 처리) — application.yml 옵션 추가, BENCHMARK.md 뼈대 작성, 디렉토리 생성.
- **수동 작업** (사용자가 측정 후 Claude와 협업) — 실제 1000/5000 VU 측정, 결과 paste, BENCHMARK.md 표·분석 작성.

자동 작업만 마치면 이 step의 AC는 통과한다 (Acceptance Criteria 섹션 참조).

### 1. application.yml — Virtual Thread 토글

`src/main/resources/application.yml` 의 `spring:` 섹션에 추가:

```yaml
spring:
  threads:
    virtual:
      enabled: ${SPRING_THREADS_VIRTUAL_ENABLED:false}
```

> env var로 토글. 기본 false (off). 측정 시 `SPRING_THREADS_VIRTUAL_ENABLED=true ./gradlew bootRun` 으로 활성화.

### 2. BENCHMARK.md 뼈대

`docs/BENCHMARK.md` 생성:

```markdown
# Load Test Benchmark — Virtual Thread on/off

## 측정 환경

| 항목 | 값 |
|---|---|
| OS | <!-- Mac, M-series 등 사용자 환경 --> |
| RAM | <!-- 16GB 등 --> |
| Java | 21 |
| Spring Boot | 3.2.x |
| Postgres | 15 (docker) |
| Redis | 7 (docker) |
| k6 | <!-- k6 version 결과 --> |
| 측정 일자 | <!-- YYYY-MM-DD --> |

## 측정 절차

각 시나리오를 다음 두 모드에서 실행:

### Before (Virtual Thread OFF)
\`\`\`bash
SPRING_THREADS_VIRTUAL_ENABLED=false ./gradlew bootRun &
sleep 15

k6 run --out json=loadtest/results/before-distributed.json     loadtest/scenario-distributed.js
k6 run --out json=loadtest/results/before-payment-flow.json    loadtest/scenario-payment-flow.js
k6 run --out json=loadtest/results/before-queue-burst.json     loadtest/scenario-queue-burst.js
k6 run --out json=loadtest/results/before-seat-contention.json loadtest/scenario-seat-contention.js
\`\`\`

### After (Virtual Thread ON)
\`\`\`bash
SPRING_THREADS_VIRTUAL_ENABLED=true ./gradlew bootRun &
sleep 15

k6 run --out json=loadtest/results/after-distributed.json     loadtest/scenario-distributed.js
k6 run --out json=loadtest/results/after-payment-flow.json    loadtest/scenario-payment-flow.js
k6 run --out json=loadtest/results/after-queue-burst.json     loadtest/scenario-queue-burst.js
k6 run --out json=loadtest/results/after-seat-contention.json loadtest/scenario-seat-contention.js
\`\`\`

## 결과

### 시나리오 1: Distributed (1000 VU ramping)

| 지표 | Before | After | Δ |
|---|---|---|---|
| 성공률 | <!-- % --> | <!-- % --> | <!-- ±% --> |
| Throughput (req/s) | <!-- N --> | <!-- N --> | <!-- ±% --> |
| p50 latency | <!-- ms --> | <!-- ms --> | <!-- ±% --> |
| p95 latency | <!-- ms --> | <!-- ms --> | <!-- ±% --> |
| p99 latency | <!-- ms --> | <!-- ms --> | <!-- ±% --> |

### 시나리오 2: Payment Flow (100 VU 2m e2e)

| 지표 | Before | After | Δ |
|---|---|---|---|
| 성공률 | | | |
| Throughput | | | |
| p50 latency | | | |
| p95 latency | | | |
| p99 latency | | | |

### 시나리오 3: Queue Burst (5000 VU)

| 지표 | Before | After | Δ |
|---|---|---|---|
| 성공률 | | | |
| Throughput | | | |
| p95 latency | | | |
| p99 latency | | | |

### 시나리오 4: Seat Contention (1000 VU 정확성)

| 지표 | Before | After |
|---|---|---|
| 성공 카운트 | | |
| 충돌 카운트 | | |
| **정확성 보장** | <!-- ✓ 1 성공·999 실패 --> | <!-- ✓ --> |

> 핵심: virtual thread on/off 와 무관하게 **정확성은 동일하게 유지**되어야 한다. throughput·latency는 변할 수 있어도 동시성 정확성이 깨지면 회귀.

## 분석

<!-- 측정 후 작성. 다음 항목을 다룰 것:
1. Virtual Thread가 throughput에 미친 영향 (특히 I/O 바운드 — DB·Redis 호출)
2. Latency 분포 변화 (p95, p99)
3. 정확성 회귀 여부
4. 트레이드오프 (메모리, 컨텍스트 스위칭)
5. Production 도입 권장 여부
-->

## 결론

<!-- 한 줄 요약. 예: "Virtual Thread 도입으로 distributed 시나리오 throughput +N%, p95 latency -N%. 정확성 회귀 없음. 도입 권장." -->
```

### 3. 자동 작업 후 수동 작업 가이드

이 step의 자동 작업이 완료되면 `phases/5-loadtest/index.json` step 2를 `completed`로 마킹하되, **summary에 명확히 표시**:

```
"summary": "application.yml virtual thread 토글 + BENCHMARK.md 뼈대 작성. ★ 측정·표 채우기는 사용자 수동 진행 — `SPRING_THREADS_VIRTUAL_ENABLED=false/true ./gradlew bootRun` 후 4개 시나리오 k6 run 결과 paste 필요."
```

## Acceptance Criteria (자동 — Claude 세션이 검증)

```bash
# 1. application.yml 변경 검증
grep -A 3 "threads:" src/main/resources/application.yml | grep "virtual"
grep "SPRING_THREADS_VIRTUAL_ENABLED" src/main/resources/application.yml

# 2. BENCHMARK.md 뼈대 존재
ls docs/BENCHMARK.md

# 3. 빌드 정상
./gradlew build
```

위 모두 통과해야 한다.

## Acceptance Criteria (수동 — 사용자 협업, 본 step 완료 후)

> 본 항목은 step status `completed` 마킹과 별개. 사용자가 step 종료 후 시간 들여 진행. 결과 정리는 다음 세션에서 Claude와 함께.

1. k6 설치 (`brew install k6`).
2. before/after 측정:
   - `SPRING_THREADS_VIRTUAL_ENABLED=false`로 4개 시나리오 측정 → `loadtest/results/before-*.json`
   - `SPRING_THREADS_VIRTUAL_ENABLED=true`로 4개 시나리오 측정 → `loadtest/results/after-*.json`
3. 결과를 Claude에 paste → BENCHMARK.md 표·분석 채움.
4. 정확성 회귀 검증: seat-contention 시나리오의 `seat_reserve_success == 1` 임계값이 둘 다에서 통과.

## 검증 절차

1. **자동 AC 커맨드 실행**.
2. 아키텍처 체크리스트:
   - `application.yml` 변경 1줄 (env var 토글). 기본값 false 유지.
   - `docs/BENCHMARK.md`는 측정 환경·절차·시나리오별 표·분석·결론 섹션 모두 포함.
   - 표는 빈 칸이지만 구조 명확 (사용자가 채울 자리 명확).
3. `phases/5-loadtest/index.json`의 step 2를 업데이트:
   - 자동 작업 통과 → `"status": "completed"` + summary에 "측정·표 채우기는 사용자 수동 진행" 명시.
   - application.yml 변경으로 빌드 깨짐 → `"status": "error"`.
4. **phase 5 자동 부분 완료** — index.json 자동 마킹.

## 금지사항

- application.yml에 `spring.threads.virtual.enabled` 외 다른 변경 금지. 본 step은 토글만.
- `SPRING_THREADS_VIRTUAL_ENABLED` 기본값을 `true`로 두지 마라. 안전한 기본값은 false (단계적 도입).
- 시나리오 파일 추가 작성 금지 (step 1 영역).
- BENCHMARK.md에 임의 수치 채워 넣지 마라. 측정 안 한 값을 추정으로 적으면 신뢰성 0. **반드시 빈 칸 + 사용자 협업으로 채움**.
- application.yml에 secret·하드코딩 추가 금지 (이전 phase 규칙).
- 백엔드 도메인 코드 변경 금지.
- 측정 결과 파일(`loadtest/results/*.json`)을 git에 커밋하지 마라 — gitignore 됨.
- BENCHMARK.md를 docs/가 아닌 다른 위치(예: 루트)에 두지 마라.
- 기존 phase 1~4 테스트 깨뜨리지 마라 — 본 step은 백엔드 변경이 application.yml 1줄뿐이라 영향 미미하나, 빌드 검증 필수.
