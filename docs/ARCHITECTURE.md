# 아키텍처

## 디렉토리 구조
```
src/main/java/com/harness/ticket/
├── global/
│   ├── config/           # Redis, Security, Swagger 등 Bean 설정
│   ├── exception/        # 공통 예외 + GlobalExceptionHandler
│   ├── response/         # ApiResponse, ErrorCode
│   └── security/         # JwtProvider, JwtAuthFilter, SecurityConfig
├── auth/
│   ├── controller/
│   ├── service/
│   ├── domain/           # User 엔티티
│   ├── repository/
│   └── dto/
├── concert/              # Concert, Seat 엔티티 + 조회 API
├── reservation/          # Reservation 엔티티 + 상태머신 + Redis 좌석 선점
└── queue/                # WaitingQueue (Redis Sorted Set 래퍼 + 워커)

src/main/resources/
├── application.yml
└── db/migration/         # Flyway 마이그레이션 (V1__init.sql ...)

src/test/java/...         # 패키지 구조 미러링. 단위 + 통합 테스트.
loadtest/                 # k6 시나리오 + 리포트
docker-compose.yml        # 로컬 Postgres + Redis
```

## 패턴
- **Layered 아키텍처** — Controller → Service → Repository. Controller는 DTO 변환만, Service는 트랜잭션 경계와 외부 I/O 오케스트레이션, Repository는 영속성.
- **도메인 로직은 엔티티 내부에** — 특히 상태 전이(`Reservation.pay()` 등). Anemic 도메인 금지.
- **Redis 접근은 Service 계층 전용** — Controller/Entity에서 `RedisTemplate` 직접 사용 금지. Redis 키는 `global/` 또는 해당 도메인의 상수로 관리.
- **테스트 피라미드** — 도메인/Service 단위는 JUnit5 + Mockito, Repository/Redis 경로는 `@SpringBootTest` + Testcontainers.

## 데이터 흐름
```
Client
  │  Authorization: Bearer <jwt>
  ▼
JwtAuthFilter ──(invalid)──> 401
  │
  ▼
Controller ──> Service ──> Repository(JPA) ──> PostgreSQL
                 │
                 └──> RedisTemplate ──> Redis (seat lock / waiting queue)
```

### 좌석 선점 시퀀스
```
POST /reservations  {showId, seatId}
  ├─ (선택) WaitingQueue 입장권 검증
  ├─ Redis SET NX PX seat:{showId}:{seatId}  (TTL 10분)
  │    ├─ 성공 → Reservation(PENDING) DB insert, 201
  │    └─ 실패 → 409 Conflict (SEAT_ALREADY_HELD)
  └─ @Transactional 경계: Redis 성공 후 DB 실패 시 Redis 키 DEL로 보상
```

### 결제 확정 시퀀스
```
POST /reservations/{id}/pay
  ├─ @Transactional
  ├─ Reservation 조회 (userId 일치 + 선점 Redis 키 존재 재검증)
  ├─ mock PG 호출
  ├─ 성공: Reservation.pay() → status=PAID, Redis 키 DEL
  └─ 실패: Reservation.cancel() → status=CANCELLED, Redis 키 DEL
```

### 인증 시퀀스
```
POST /auth/signup   {username, password}
  → BCrypt 해시 저장, 201

POST /auth/login    {username, password}
  → 검증 → access(15분) + refresh(14일) 발급
  → Redis SET refresh:{userId}:{jti}  (TTL 14일)
  → 응답 {accessToken, refreshToken}

POST /auth/refresh  {refreshToken}
  → JWT 검증 + Redis 키 존재 확인
  │    ├─ 존재하지 않음 → 이미 회전됐거나 탈취 의심 → 해당 유저의 모든 refresh 삭제, 401
  │    └─ 존재 → 새 access + 새 refresh 발급
  │       ├─ 이전 refresh 키 DEL
  │       └─ 새 refresh 키 SET (TTL 14일)

POST /auth/logout (Bearer access)
  → 해당 유저의 모든 refresh 키 DEL
```

### 대기열 시퀀스
```
POST /queue/{showId}/enter   → ZADD NX queue:{showId} <now_ms> <userId>
GET  /queue/{showId}/me      → ZRANK + 앞 대기자 수 기반 ETA
(worker)                      → N초마다 ZPOPMIN N개 → admit:{showId} Set에 추가 (TTL 10분)
POST /reservations (선점)     → admit:{showId} 확인 없으면 403
```

## 상태 관리
- **PostgreSQL**: 영속 상태 — User, Concert, Seat, Reservation.
- **Redis**: 휘발 상태 — 좌석 선점 락(`seat:*`), 대기열(`queue:*`), 입장 허가(`admit:*`), refresh 토큰 저장소(`refresh:{userId}:{jti}`, TTL 14일).
- **JVM 메모리**: 상태 저장 금지. 수평 확장 가능한 상태로 유지.

## 컨벤션
- 패키지 최상위는 도메인(auth, concert, reservation, queue). 기술 레이어 최상위 패키지 금지(`controller/`, `service/` 최상위 금지).
- DTO 네이밍: `XxxRequest`, `XxxResponse`. 엔티티는 DTO로 절대 직접 응답하지 않는다.
- Redis 키 상수는 함수로 제공: `RedisKeys.seat(showId, seatId)` 식. 문자열 리터럴 금지.
