# 프로젝트: harness_ticket (콘서트 티켓 예매 시스템)

## 기술 스택
- Spring Boot 3.x, Java 21, Gradle (Groovy DSL)
- Spring Data JPA + PostgreSQL 15 + Flyway
- Spring Data Redis + Lettuce
- Spring Security + JWT (io.jsonwebtoken / jjwt)
- Spring Boot Actuator (헬스체크: `/actuator/health`)
- Lombok (DTO/엔티티 보일러플레이트 제거)
- JUnit 5 + Mockito + Testcontainers (Postgres, Redis)
- k6 (loadtest/)

## 아키텍처 규칙
- CRITICAL: 예약 상태 전이는 `Reservation` 엔티티 내부 메서드(`reserve()` / `pay()` / `cancel()`)로만 수행한다. 서비스·컨트롤러에서 `reservation.setStatus(...)` 또는 status 필드 직접 대입 금지.
- CRITICAL: 좌석 선점은 `SET NX PX` **단일 원자 연산**으로만 처리한다. `EXISTS` 후 `SET` 같은 2단계 처리 금지 (race condition).
- CRITICAL: Redis 키는 `RedisKeys` 같은 상수 헬퍼로 생성한다. 문자열 리터럴을 코드에 흩뿌리지 않는다. 네이밍은 `{domain}:{id}...` 형식 (예: `seat:{showId}:{seatId}`, `queue:{showId}`, `admit:{showId}`).
- CRITICAL: 엔티티를 그대로 응답하지 말고 반드시 DTO(`XxxResponse`)로 변환한다.
- 도메인 로직은 엔티티에. 서비스는 트랜잭션 경계 + 외부 I/O 오케스트레이션만 담당한다.
- 패키지 최상위는 도메인(`auth`, `concert`, `reservation`, `queue`). 기술 레이어 최상위 패키지(`controller/`, `service/` 등) 금지.
- 컨트롤러에서 비즈니스 분기 금지. DTO 변환·검증 어노테이션·HTTP 상태 매핑만 담당한다.

## 테스트 규칙
- CRITICAL: 동시성이 걸린 로직(좌석 선점, 큐 입장, 결제 확정)은 멀티스레드 경합 테스트를 반드시 작성한다. (예: 100 스레드가 같은 좌석 선점 시 성공 1·실패 99)
- Redis/DB 경로 통합 테스트는 Testcontainers로 실제 인프라를 띄운다. H2·임베디드 Redis 사용 금지 — 프로덕션 동작과 다르다.
- 단위 테스트는 Mockito. 엔티티 상태 전이 테스트는 DB 없이 POJO로 검증한다.

## 개발 프로세스
- CRITICAL: 새 기능은 테스트부터 작성하고, 테스트가 통과하는 구현을 작성한다 (TDD).
- 커밋 메시지는 conventional commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).

## 명령어
```
docker-compose up -d          # Postgres + Redis 기동 (로컬 실행 선행조건)
./gradlew bootRun             # 애플리케이션 실행
./gradlew build               # 빌드 + 전체 테스트
./gradlew test                # 테스트만
./gradlew test --tests "*ReservationServiceTest"   # 특정 테스트
k6 run loadtest/reserve.js    # 부하 테스트
```
