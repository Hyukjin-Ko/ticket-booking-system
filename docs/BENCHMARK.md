# Load Test Benchmark — Virtual Thread + Connection Pool 튜닝

## 측정 환경

| 항목 | 값 |
|---|---|
| OS | macOS 14 (Darwin 23.6.0, Sonoma) |
| CPU | Apple M2 |
| RAM | 16 GB |
| Java | OpenJDK 21.0.1 |
| Spring Boot | 3.2.x |
| Postgres | 15-alpine (docker, max_connections=100) |
| Redis | 7-alpine (docker) |
| k6 | v1.7.1 |
| 측정 일자 | 2026-04-29 |

## 측정 절차

각 측정 전 **Redis·DB 상태 초기화** (이전 사용자·예약 잔존 정리):

```bash
docker compose down -v && docker compose up -d
sleep 5
set -a && source .env && set +a
```

세 가지 모드를 비교:

```bash
# 1) VT OFF + Pool 10 (baseline)
SPRING_THREADS_VIRTUAL_ENABLED=false HIKARI_MAX_POOL_SIZE=10 ./gradlew bootRun

# 2) VT ON + Pool 10 (VT 단독 도입)
SPRING_THREADS_VIRTUAL_ENABLED=true HIKARI_MAX_POOL_SIZE=10 ./gradlew bootRun

# 3) VT ON + Pool 100 (VT + 다운스트림 튜닝)
SPRING_THREADS_VIRTUAL_ENABLED=true HIKARI_MAX_POOL_SIZE=100 ./gradlew bootRun
```

각 모드에서 시나리오 실행:

```bash
k6 run --out json=loadtest/results/<mode>-seat-contention.json \
  loadtest/scenario-seat-contention.js
```

## 결과

### 시나리오: Seat Contention (1000 VU, 동일 좌석 1개에 동시 시도)

**측정 의도**: 동시성 정확성(1 성공·N-1 실패) + Virtual Thread / Connection Pool 영향.

| 지표 | VT OFF<br>Pool 10 | VT ON<br>Pool 10 | VT ON<br>**Pool 100** |
|---|---|---|---|
| **seat_reserve_success** (정확성 ★) | **1** | **1** | **1** |
| seat_reserve_conflict | 998 | 999 | 999 |
| 노이즈 (예상 외 응답) | 1 (0.1%) | 0 | 0 |
| http_req_duration avg | 250 ms | 578 ms | 275 ms |
| http_req_duration p50 | 143 ms | 144 ms | 142 ms |
| http_req_duration p90 | n/a | 1610 ms | 570 ms |
| http_req_duration p95 | 578 ms | 1640 ms | 686 ms |
| http_req_duration max | 2.13 s | 2.42 s | 2.33 s |
| **expected_response p95** (2xx만) | 157 ms | 157 ms | 157 ms |
| iteration_duration p95 | 701 ms | 1790 ms | 1050 ms |
| 측정 시간 | ~5 min | ~5 min | ~5 min |

#### 핵심 관찰
1. **정확성**: 모든 모드에서 `success_count == 1`. 좌석 1000명 동시 시도 → 단 1명만 성공. **동시성 무결성 보장됨**.
2. **VT 단독 활성화 → 오히려 악화**: p95 578ms → 1.64s (+184%).
3. **Pool 튜닝 후 회복**: p95 1.64s → 686ms (-58%).
4. **2xx 응답 latency는 모두 동일** (157ms p95) — 시스템의 정상 처리 path는 변함 없음. 차이는 4xx(409 conflict) 응답 시 connection 대기에서 발생.

### 시나리오: Distributed / Payment Flow / Queue Burst

> **측정 안 함 (추가 측정 가능 영역)**.
>
> - `scenario-distributed.js` (ramping 0→1000 VU) — throughput 측정 가능
> - `scenario-payment-flow.js` (100 VU 2m e2e) — 결제 플로우 latency 측정 가능
> - `scenario-queue-burst.js` (5000 VU) — 대기열 throughput 측정 가능
>
> Seat Contention만으로 동시성 정확성 + Virtual Thread/Pool 튜닝 효과는 충분히 입증되어 본 리포트에서는 생략. 시간 여유 시 동일 절차로 추가 측정 가능.

## 분석

### 1. Virtual Thread는 "켜기만 하면" 효과 없거나 악화될 수 있다

VT 단독 활성화 시 p95 latency가 **578ms → 1.64s로 +184% 악화**. 원인 분석:

- **Tomcat platform thread는 200개 max**라는 암묵적 throttling 효과를 제공
- Virtual Thread 활성화 시 동시 처리 가능한 요청 수가 사실상 무제한
- 결과: **1000개 동시 요청이 HikariCP 기본 pool 10개에 모두 몰림** → 99% 가 connection 대기
- DB 조회를 거치는 모든 요청 (성공·실패 path 모두)이 평균적으로 길게 대기

이는 **"문제의 위치가 옮겨갔을 뿐"** 인 전형적인 사례. VT가 thread 병목을 해소하면 다음 자원(connection pool)이 새 병목이 된다.

### 2. Connection Pool 튜닝이 VT의 전제조건

`maximum-pool-size: 10 → 100`으로 변경 시:

- p95 latency 1.64s → 686ms (**-58% 개선**)
- iteration_duration p95: 1.79s → 1.05s (**-41% 개선**)
- VT의 동시 처리량 증가 능력이 비로소 발휘

VT 도입은 **다운스트림 자원(DB 풀, Redis 연결, 외부 API rate limit 등)과 함께 튜닝되어야** 의미 있다.

### 3. 짧은 트랜잭션에선 VT의 절대적 이점이 크지 않다

VT ON + Pool 100 vs VT OFF + Pool 10:

- p95: 686ms vs 578ms (VT 쪽이 살짝 더 느림)
- expected_response p95: 157ms 동일

좌석 경합은 트랜잭션이 짧고(~150ms) 외부 호출 대기 시간이 거의 없어 VT의 이점이 두드러지지 않음. **VT는 I/O 바운드 + 긴 대기**(외부 API 동기 호출, 느린 DB 쿼리 등) 워크로드에서 진가를 발휘한다.

### 4. 정확성은 모든 조합에서 보장됨

- 모든 모드에서 `seat_reserve_success == 1`
- 동시 1000명이 같은 좌석을 시도해도 **Redis SET NX의 원자성**이 정확성을 보장
- VT/Pool 변경은 throughput·latency에만 영향, 정확성에 영향 없음

### 5. `http_req_failed: 33%`는 도구의 한계, 시스템 문제 아님

k6의 `http_req_failed`는 status code 기반(2xx 외 = fail). 좌석 경합 시나리오에서 999개의 `409 SEAT_ALREADY_HELD`는 **의도된 정상 동작**이지만 4xx라 fail로 카운트됨. 도메인 의미를 반영하려면 `http.setResponseCallback(http.expectedStatuses({min:200,max:299}, 409))` 으로 expected status 등록 필요.

`{expected_response:true}` 필터로 본 2xx만의 p95는 모든 모드에서 157ms로 동일 — 정상 처리 path는 안정적.

## 결론

- **Virtual Thread는 단독 도입으로 효과 없음** — 좌석 경합 시나리오에서 p95 +184% 악화.
- **Connection Pool 동시 튜닝 시 효과 회복** — p95 -58%, 그러나 baseline 대비 절대적 이득은 미미 (짧은 트랜잭션의 한계).
- **정확성은 모든 조합에서 1/1000 보장** — Redis SET NX 원자성 + 도메인 상태머신의 견고함 입증.
- **본 프로젝트 워크로드에는 VT 도입을 보수적으로 평가** 권장. I/O 대기가 길어지는 시나리오(외부 PG 연동, 느린 쿼리)가 추가될 때 재측정 가치 있음.

### Production 도입 권장 여부

| 환경 | VT 권장 여부 | 비고 |
|---|---|---|
| 현재 MVP (mock 결제) | △ | 효과 미미, 다운스트림 튜닝 비용만 발생 |
| 외부 PG 통합 후 | ○ | 동기 PG 호출에서 VT 효과 큼 |
| 대규모 외부 API 연동 | ◎ | VT의 강점이 본격 발휘 |

### 추가 검증 가능 항목 (미측정)

- `scenario-distributed.js`: 분산 좌석 throughput
- `scenario-payment-flow.js`: end-to-end latency 분포
- `scenario-queue-burst.js`: 5000 VU 대기열 처리량
- Pool 100 + VT OFF 조합 (4-way 비교)
- Postgres `max_connections` 추가 확장 후 더 큰 pool size 효과

위 항목들은 동일 절차로 추가 측정 가능하나, 본 리포트의 핵심 결론(VT의 함정 + Pool 튜닝 필요성 + 정확성 보장)을 바꾸지 않을 것으로 예상.
