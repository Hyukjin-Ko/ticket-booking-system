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
- CRITICAL: 예약 상태 전이는 `Reservation` 엔티티 내부 메서드(`pay()` / `cancel()`)로만 수행한다. 서비스·컨트롤러에서 `reservation.setStatus(...)` 또는 status 필드 직접 대입 금지. 상태는 3개(`PENDING`, `PAID`, `CANCELLED`).
- CRITICAL: 좌석 선점은 `SET key {userId} NX PX 600000` **단일 원자 연산**으로만 처리한다. `EXISTS` 후 `SET` 같은 2단계 처리 금지 (race condition). 키 value는 항상 `userId`.
- CRITICAL: Redis 키는 `RedisKeys` 헬퍼로만 생성한다. 문자열 리터럴 금지. 네이밍은 `{domain}:{id}...` (예: `seat:{showId}:{seatId}`, `queue:{showId}`, `admit:{showId}`, `count:user:{userId}:{showId}`, `refresh:{userId}`).
- CRITICAL: 엔티티 직접 응답 금지. 항상 DTO(`XxxResponse`)로 변환.
- CRITICAL: 모든 시간은 `java.time.Instant` (UTC). `LocalDateTime` / `Date` / `ZonedDateTime` 사용 금지. JVM은 `-Duser.timezone=UTC`로 기동.
- CRITICAL: 시간 의존 로직은 `Clock` Bean 주입으로만. `Instant.now()` 직접 호출 금지 — 테스트 결정성 깨진다.
- CRITICAL: 비밀번호 평문·BCrypt 해시·JWT 토큰을 어떤 로그에도 출력 금지. 사용자 식별은 `userId`(숫자)만 허용.
- CRITICAL: 모든 RequestDto에 `@Valid` + Bean Validation 어노테이션(`@NotBlank`, `@Size` 등) 적용. 검증은 컨트롤러 진입점에서.
- CRITICAL: 좌석 선점 시 Redis SET 성공 후 DB insert 실패는 반드시 보상 트랜잭션으로 Redis 키 DEL + 카운터 DECR. `TransactionSynchronizationManager.registerSynchronization`의 afterCompletion 콜백 사용.
- 도메인 로직은 엔티티에. 서비스는 트랜잭션 경계 + 외부 I/O 오케스트레이션만 담당한다.
- 패키지 최상위는 도메인(`auth`, `concert`, `reservation`, `queue`) + `global`. 기술 레이어 최상위 패키지(`controller/`, `service/` 등) 금지.
- 컨트롤러에서 비즈니스 분기 금지. DTO 변환·검증·HTTP 상태 매핑만.
- `Reservation`은 `@Version` 낙관락. 동시 결제 충돌 시 `OptimisticLockException` → 409.

## 테스트 규칙
- CRITICAL: 동시성이 걸린 로직(좌석 선점, 큐 입장, 결제 확정)은 멀티스레드 경합 테스트를 반드시 작성한다. (예: 100 스레드가 같은 좌석 선점 시 성공 1·실패 99 + DB Reservation row 1개)
- CRITICAL: Redis/DB 경로 통합 테스트는 Testcontainers로 실제 인프라를 띄운다. H2·임베디드 Redis 사용 금지. `@Container static withReuse(true)`로 컨테이너 재사용.
- 단위 테스트는 Mockito. 엔티티 상태 전이 테스트는 DB 없이 POJO로 검증.
- 시간 의존 테스트는 `Clock.fixed(...)`로 시간 고정. wall-clock 의존 테스트 금지 (flaky).
- mock 결제는 `Random` Bean을 fixed seed로 주입해 결정성 확보.

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
