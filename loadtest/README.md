# Load Test (k6)

## 설치
```bash
brew install k6
```

## 실행 전 준비
```bash
# 1. 인프라 + 앱 기동
docker-compose up -d
set -a && source .env && set +a
./gradlew bootRun &

# 2. 헬스체크 확인
curl -fsS http://localhost:8080/actuator/health
```

## 시나리오

| 시나리오 | 파일 | 부하 | 목적 |
|---|---|---|---|
| Smoke | `smoke.js` | 10 VU 30s | 기본 동작 확인 |
| 좌석 경합 | `scenario-seat-contention.js` | 1000 VU | 정확성 (1성공·999실패) |
| 분산 예매 | `scenario-distributed.js` | 1000 VU | throughput |
| 전체 플로우 | `scenario-payment-flow.js` | 100 VU | end-to-end latency |
| 대기열 폭주 | `scenario-queue-burst.js` | 5000 VU | queue throughput |

## 실행
```bash
# Smoke
k6 run loadtest/smoke.js

# 핵심 시나리오 (결과 JSON 저장)
k6 run --out json=loadtest/results/seat-contention.json loadtest/scenario-seat-contention.js

# Virtual Thread 비교
SPRING_THREADS_VIRTUAL_ENABLED=false ./gradlew bootRun &  # before
SPRING_THREADS_VIRTUAL_ENABLED=true  ./gradlew bootRun &  # after
```

## 환경 변수
| 변수 | 기본값 | 설명 |
|---|---|---|
| BASE_URL | http://localhost:8080 | 대상 서버 |

> 결과 파일은 `loadtest/results/` 에 저장 (gitignore).
