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
```bash
SPRING_THREADS_VIRTUAL_ENABLED=false ./gradlew bootRun &
sleep 15

k6 run --out json=loadtest/results/before-distributed.json     loadtest/scenario-distributed.js
k6 run --out json=loadtest/results/before-payment-flow.json    loadtest/scenario-payment-flow.js
k6 run --out json=loadtest/results/before-queue-burst.json     loadtest/scenario-queue-burst.js
k6 run --out json=loadtest/results/before-seat-contention.json loadtest/scenario-seat-contention.js
```

### After (Virtual Thread ON)
```bash
SPRING_THREADS_VIRTUAL_ENABLED=true ./gradlew bootRun &
sleep 15

k6 run --out json=loadtest/results/after-distributed.json     loadtest/scenario-distributed.js
k6 run --out json=loadtest/results/after-payment-flow.json    loadtest/scenario-payment-flow.js
k6 run --out json=loadtest/results/after-queue-burst.json     loadtest/scenario-queue-burst.js
k6 run --out json=loadtest/results/after-seat-contention.json loadtest/scenario-seat-contention.js
```

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
