# Architecture Decision Records

## 철학
**동시성·일관성 스토리를 보여줄 수 있는 최소 구성을 최우선으로 한다.** 외부 의존성은 PostgreSQL과 Redis만. 기능 범위는 좁히고, "1000 TPS에서 어떻게 버티는지"를 수치로 증명할 수 있는 구조를 만든다.

---

### ADR-001: Spring Boot 3 + Java 21
**결정**: Spring Boot 3.x, Java 21, Gradle(Groovy DSL).
**이유**: 매칭 도메인(토스·인터파크 등)의 표준 스택. Java 21의 virtual thread로 I/O 바운드 워크로드 부하 스토리를 확장할 여지가 있다.
**트레이드오프**: 빌드·기동 시간이 무겁고, 로컬 iteration 속도가 Node 대비 느리다.

### ADR-002: 좌석 선점은 Redis `SET NX PX` 원자 연산
**결정**: 키 `seat:{showId}:{seatId}`에 `SET NX PX 600000`(10분). 성공 시 해당 유저가 선점권을 획득, 실패 시 409.
**이유**: DB 비관/낙관락 대비 경합 처리 비용이 낮다. TTL로 "결제 미완 유령 선점"이 자동 해소된다. 원자 연산 1번으로 경합이 끝난다.
**트레이드오프**: Redis 단일 장애점이 생긴다. Redis ↔ DB 사이 이중 진실 문제가 있어, 결제 확정 시점에 DB 트랜잭션으로 재검증한다.

### ADR-003: 예약 상태 전이는 도메인 엔티티 내부 메서드로만
**결정**: `Reservation` 엔티티에 `reserve()`, `pay()`, `cancel()` 메서드를 두고, 허용되지 않는 전이는 `IllegalStateException`. 서비스 계층은 상태 필드에 직접 대입하지 않는다.
**이유**: 서비스에서 `if (status == X) status = Y` 분기는 규칙이 여러 곳에 퍼지고 깨지기 쉽다. 전이 규칙을 도메인 한 곳에 가둔다.
**트레이드오프**: 엔티티가 다소 무거워지고, JPA setter 노출 관리가 필요하다.

### ADR-004: JWT Access + Refresh (Rotation)
**결정**:
- Access 토큰: HS256, 만료 15분. `Authorization: Bearer`로 전달.
- Refresh 토큰: HS256, 만료 14일. 응답 body로 전달하고 클라이언트가 보관.
- Refresh 토큰은 Redis에 `refresh:{userId}:{jti}` 키로 저장(TTL 14일). 서버에서 즉시 revoke 가능하게 한다.
- `POST /auth/refresh` 호출 시: old refresh 검증 → 새 access + 새 refresh 발급 → old refresh 키 즉시 삭제 (**rotation**).
- Reuse detection: 이미 삭제된 refresh로 재발급 시도가 오면 해당 유저의 모든 refresh를 무효화한다 (탈취 의심).
- `POST /auth/logout`: 해당 유저의 refresh 키 삭제.

**이유**: Access 수명이 짧을수록 탈취 피해가 작다. Rotation + reuse detection은 refresh 탈취 감지에 효과적인 표준 패턴. 서버 측 저장(Redis)으로 즉시 revoke가 가능하다.
**트레이드오프**: 재발급 경로·탈취 감지 로직으로 구현 복잡도가 늘고, 모든 요청 흐름에 refresh 실패 시 재로그인 UX 분기가 필요하다. Redis 장애 시 재발급 불가.

### ADR-005: WaitingQueue = Redis Sorted Set
**결정**: 키 `queue:{showId}`, score = 진입 timestamp(ms). 진입 `ZADD NX`, 순번 조회 `ZRANK`, 워커가 일정 간격마다 상위 N개 `ZPOPMIN`하여 "입장 허가" 상태로 이동.
**이유**: FIFO + 순번 O(log N) + 원자적 pop을 단일 자료구조로 해결.
**트레이드오프**: 사용자 이탈·타임아웃 정리 로직을 별도로 구현해야 한다.

### ADR-006: 결제는 mock
**결정**: `POST /reservations/{id}/pay`는 외부 PG 없이 90% 성공/10% 실패로 응답한다.
**이유**: MVP. 실제 PG는 테스트 키 발급·웹훅 수신 인프라가 필요하다.
**트레이드오프**: 실제 결제 실패의 멱등성·롤백 시나리오가 단순화된다.

### ADR-007: 테스트 인프라는 Testcontainers
**결정**: 통합 테스트는 Testcontainers로 실제 Postgres/Redis를 띄워 검증. H2·임베디드 Redis 금지.
**이유**: 동시성·SQL 방언·Redis 커맨드 동작이 프로덕션과 동일해야 부하 테스트 결과에 신뢰가 생긴다.
**트레이드오프**: 테스트 실행 시간 증가, Docker 필수.
