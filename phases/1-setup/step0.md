# Step 0: bootstrap

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — 프로젝트 전반 규칙. CRITICAL 규칙 11개를 모두 숙지.
- `/docs/PRD.md` — 핵심 기능 / MVP 범위 / API 스타일.
- `/docs/ARCHITECTURE.md` — 디렉토리 구조와 시퀀스. 본 step에서 만들 패키지 트리는 "디렉토리 구조" 섹션 참조.
- `/docs/ADR.md` — 특히 ADR-001(스택), ADR-007(테스트 인프라), ADR-008(시간/타임존), ADR-009(로깅).

읽고 나서 시작하라. 설계 의도에서 벗어나면 안 된다.

## 작업

Spring Boot 인프라를 구축한다. 이 step이 끝나면 `./gradlew build` 가 통과하고 `curl /actuator/health` 가 `{"status":"UP"}` 을 반환해야 한다.

### 1. Gradle 프로젝트 스캐폴딩

- Gradle **8.x Groovy DSL** 사용. `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper/*`.
- Java toolchain 21 (`gradle.properties` 또는 `build.gradle`).
- `settings.gradle`에 `rootProject.name = 'harness-ticket'`.
- `bootRun` 태스크에 `jvmArgs = ['-Duser.timezone=UTC']` 적용 (CRITICAL: ADR-008).

### 2. 의존성 (build.gradle)

Spring Boot **3.2.x BOM** 고정. 다음 starter / 라이브러리 추가:

- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-data-redis`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql` (runtimeOnly)
- `org.projectlombok:lombok` (compileOnly + annotationProcessor)

테스트:
- `spring-boot-starter-test`
- `org.testcontainers:postgresql`
- `org.testcontainers:junit-jupiter`
- `com.redis:testcontainers-redis`

**금지 의존성** (지금 추가하지 마라, phase 2-auth에서 추가):
- `spring-boot-starter-security`
- `io.jsonwebtoken:jjwt-*`

이유: Security 의존성을 추가하는 순간 모든 endpoint가 401이 되어 헬스체크부터 막힌다. phase 2-auth 단계에서 `SecurityConfig`와 함께 일관 추가.

### 3. Spring Boot 메인

`src/main/java/com/harness/ticket/TicketApplication.java`:

```java
@SpringBootApplication
@EnableScheduling
public class TicketApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
```

`@EnableScheduling`을 메인에 두는 이유: 추후 `PendingReservationCleaner`, `AdmitWorker` 등이 `@Scheduled` 사용한다 (phase 3, 4).

### 4. application.yml

`src/main/resources/application.yml` — 환경변수 기반. 필수 키:

```yaml
spring:
  application.name: harness-ticket
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/ticket}
    username: ${DB_USERNAME:ticket}
    password: ${DB_PASSWORD:ticket}
  jpa:
    hibernate.ddl-auto: validate           # CRITICAL: Flyway가 schema 관리
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
  data.redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    timeout: 1000ms
    lettuce.shutdown-timeout: 100ms
  jackson:
    serialization.write-dates-as-timestamps: false
    time-zone: UTC

management:
  endpoints.web.exposure.include: health
  endpoint.health.show-details: never      # CRITICAL: 보안 (ADR-009)

logging.pattern.level: "%5p [%X{requestId}]"
```

`src/main/resources/application-test.yml` — test profile은 비워두거나 testcontainers DynamicPropertySource를 사용하므로 최소 설정만.

### 5. Flyway baseline

`src/main/resources/db/migration/V1__baseline.sql`:

```sql
-- baseline: 빈 schema. 이후 step에서 도메인 테이블 추가.
SELECT 1;
```

### 6. Clock 주입 설정

`src/main/java/com/harness/ticket/global/config/ClockConfig.java`:

```java
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

CRITICAL (ADR-008, CLAUDE.md): 다른 어떤 코드에서도 `Instant.now()` 직접 호출 금지. 항상 `Clock` 주입.

### 7. docker-compose.yml

루트에 생성. Postgres 15 + Redis 7. 둘 다 healthcheck 포함:

- Postgres: `pg_isready -U ticket -d ticket`, 5초 간격
- Redis: `redis-cli ping`, 5초 간격
- 명명된 볼륨 (`ticket_pg_data`, `ticket_redis_data`)
- 포트 바인딩: 5432, 6379

### 8. .gitignore 보강

루트의 기존 `.gitignore`에 다음 항목이 없으면 추가:

```
build/
.gradle/
.idea/
*.iml
*.log
out/
```

### 9. 테스트

- `src/test/java/com/harness/ticket/TicketApplicationTests.java` — `@SpringBootTest` context load. Testcontainers `@DynamicPropertySource`로 Postgres/Redis 주입.
- `src/test/java/com/harness/ticket/global/health/HealthIT.java` — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`. `/actuator/health` GET → 200 + body `status: "UP"` 검증.
- `src/test/java/com/harness/ticket/global/config/ClockConfigTest.java` — Clock Bean 주입되고 `Clock.systemUTC()` 와 동등(zone=UTC) 검증.

Testcontainers 헬퍼 (예: `IntegrationTestSupport` 추상 클래스 또는 `@Testcontainers` 어노테이션 + static container)는 reuse mode 활성화:

```java
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
    .withReuse(true);
```

`~/.testcontainers.properties`에 `testcontainers.reuse.enable=true` 가정 (없으면 첫 실행 시 안내 로그 발생, 동작에는 문제 없음).

## Acceptance Criteria

```bash
# 인프라 기동
docker-compose up -d

# 빌드 + 테스트 통과
./gradlew build

# 앱 실행 후 헬스체크
./gradlew bootRun &
APP_PID=$!
sleep 15
curl -fsS http://localhost:8080/actuator/health    # {"status":"UP"}
kill $APP_PID
```

위 명령들이 모두 성공해야 한다.

## 검증 절차

1. 위 AC 커맨드를 순서대로 실행한다.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md`의 "디렉토리 구조" 섹션과 일치하는가? (이 step에선 `global/config/`, `global/health/`(테스트)만 생성. 도메인 패키지는 만들지 않는다.)
   - `/docs/ADR.md`의 ADR-001(스택), ADR-007(Testcontainers), ADR-008(UTC/Instant), ADR-009(MDC)에 위배되지 않는가?
   - `/CLAUDE.md` CRITICAL 규칙(특히 시간·로깅·금지 의존성)을 위반하지 않는가?
3. 결과에 따라 `phases/1-setup/index.json`의 step 0을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "Spring Boot 3.2.x + Postgres/Redis docker-compose + actuator health + Clock UTC + Flyway baseline 구축"`
   - 수정 3회 후에도 실패 → `"status": "error"`, `"error_message": "..."`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "..."` 후 즉시 중단

## 금지사항

- `spring-boot-starter-security`, `jjwt` 의존성 추가 금지. 이유: phase 2-auth에서 일관 추가. 지금 넣으면 actuator/health 401로 막혀 step 자체가 검증 불가.
- JPA `ddl-auto`를 `create` / `create-drop` / `update`로 설정 금지. 이유: Flyway와 충돌, 운영 사고 원천. `validate` 만 허용.
- H2 / 임베디드 Redis 사용 금지. 이유: ADR-007. 통합 테스트는 Testcontainers로 실제 인프라.
- `LocalDateTime`, `Date`, `ZonedDateTime` 타입 사용 금지. 이유: ADR-008 — 모든 시간은 `Instant`.
- `Instant.now()` 직접 호출 금지. 이유: 본 step에서 만드는 `ClockConfig`가 다음 phase 도메인 코드에서 주입될 자리. 일관성 유지.
- `actuator/health` 외 다른 actuator endpoint 노출 금지. 이유: 보안 정책 (ADR-009).
- `management.endpoint.health.show-details: always` 금지. 이유: 내부 정보 노출.
- 도메인 패키지(`auth/`, `concert/`, `reservation/`, `queue/`) 빈 디렉토리 미리 생성 금지. 이유: 다음 phase에서 만든다.
- `printStackTrace()` 사용 금지. 이유: ADR-009 — SLF4J만.
- 기존 테스트를 깨뜨리지 마라 (현재는 없으나, 새로 작성한 테스트 모두 통과해야 한다).
