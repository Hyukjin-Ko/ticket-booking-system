# Step 0: security-foundation

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙 11개. 특히 시간(Clock 주입), PII 로깅 금지, Redis 키 헬퍼, DTO 변환.
- `/docs/PRD.md` — API 스타일(`{success,code,message,data}`).
- `/docs/ARCHITECTURE.md` — "인증 시퀀스" 섹션 + 디렉토리 구조의 `global/security/`.
- `/docs/ADR.md` — **ADR-004 전체** (JWT access+refresh 정책, 1 user=1 refresh, secret env var, reuse detection 등). ADR-008(Clock), ADR-009(로깅).
- 이전 phase 1-setup에서 만든 파일들:
  - `build.gradle` — 의존성 추가할 자리
  - `src/main/java/com/harness/ticket/global/config/ClockConfig.java` — Clock 주입
  - `src/main/java/com/harness/ticket/global/response/ApiResponse.java` — 응답 형식
  - `src/main/java/com/harness/ticket/global/response/ErrorCode.java` — enum 추가할 자리
  - `src/main/java/com/harness/ticket/global/exception/BusinessException.java` / `GlobalExceptionHandler.java`
  - `src/main/java/com/harness/ticket/global/logging/RequestIdFilter.java`
  - `src/main/resources/application.yml` / `application-test.yml` — 설정 추가할 자리
  - 모든 기존 테스트 (깨지지 않게)

이전 step의 `ApiResponse`, `ErrorCode`, `BusinessException`, `GlobalExceptionHandler`가 어떻게 동작하는지 정확히 이해한 뒤 작업하라. 새 ErrorCode 추가는 enum에 항목만 더하면 되고, 새 예외 처리는 GlobalExceptionHandler에 핸들러를 추가하면 된다.

## 작업

Spring Security + JWT 인프라를 구축한다. 이 step에서는 endpoint를 만들지 않는다 — 인프라만 깔고 단위 테스트로 검증. 실제 `/auth/**` API는 step 1에서 추가한다.

### 1. 의존성 추가 (build.gradle)

기존 dependencies 블록에 추가:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'

implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.6'

testImplementation 'org.springframework.security:spring-security-test'
```

`jjwt-impl`과 `jjwt-jackson`은 `runtimeOnly` 인 점 주의 (코드에서는 jjwt-api만 import).

### 2. application.yml 갱신

`src/main/resources/application.yml`에 jwt 섹션 추가 (default 박지 마라 — env var 강제):

```yaml
jwt:
  secret: ${JWT_SECRET}
  access-ttl-minutes: 15
  refresh-ttl-days: 14
```

`src/main/resources/application-test.yml`에는 테스트 전용 더미값:

```yaml
jwt:
  secret: dGVzdC1zZWNyZXQtbXVzdC1iZS1hdC1sZWFzdC0zMi1ieXRlcy1sb25n
  access-ttl-minutes: 15
  refresh-ttl-days: 14
```

위 base64 값은 32+ 바이트 더미. 테스트에서만 쓴다 (비밀이 아님).

### 3. JWT 설정 프로퍼티 클래스

`src/main/java/com/harness/ticket/global/security/JwtProperties.java`:

```java
@ConfigurationProperties(prefix = "jwt")
@Getter
@RequiredArgsConstructor
public class JwtProperties {
    private final String secret;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;
}
```

`TicketApplication`에 `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties(JwtProperties.class)` 추가.

### 4. JwtProvider — 토큰 발급/검증

`src/main/java/com/harness/ticket/global/security/JwtProvider.java`:

요구사항:
- `@Component`. 생성자에 `JwtProperties`, `Clock` 주입.
- 생성자에서 **secret 검증**: `secret.getBytes(StandardCharsets.UTF_8).length < 32` 면 `IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits)")` 던져서 startup fail.
- 시크릿 키는 `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`로 1회 생성해 필드 보관.
- 메서드 시그니처:
  ```java
  String createAccess(Long userId, String username)
  String createRefresh(Long userId)
  Jws<Claims> parseAndValidate(String token)   // 만료/위조 시 예외 던짐
  long getAccessTtlSec()                        // = accessTtlMinutes * 60
  long getRefreshTtlSec()                       // = refreshTtlDays * 86400
  ```
- Access 토큰 claims:
  - `sub` = userId (문자열)
  - `username` = username
  - `iat` = `Instant.now(clock)`
  - `exp` = `iat + Duration.ofMinutes(accessTtlMinutes)`
  - `type` = `"access"` (구분 claim)
- Refresh 토큰 claims:
  - `sub` = userId
  - `iat`, `exp` (refresh-ttl-days 기준)
  - `type` = `"refresh"`
- `parseAndValidate`는 `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)` 사용. 잘못된 서명·만료·구조 모두 `JwtException` 계열로 던짐 (호출자가 catch).

### 5. JwtAuthFilter — SecurityContext 주입

`src/main/java/com/harness/ticket/global/security/JwtAuthFilter.java`:

요구사항:
- `extends OncePerRequestFilter`, `@Component`.
- `JwtProvider` 주입.
- `doFilterInternal` 동작:
  1. `Authorization` 헤더 추출. 없거나 `"Bearer "` prefix 아니면 다음 filter로 그대로 통과 (인증 없음 상태).
  2. token 추출 후 `jwtProvider.parseAndValidate(token)` 호출.
  3. claims의 `type`이 `"access"` 가 아니면 통과 (refresh 토큰으로 endpoint 접근 차단).
  4. valid이면 `userId = Long.parseLong(claims.getSubject())`, `username = claims.get("username")` 추출.
  5. `UsernamePasswordAuthenticationToken(userId, null, List.of())` 생성 (principal로 userId만 — Long 타입). authorities는 빈 리스트 (단일 ROLE_USER라 의미 없음, MVP).
  6. `SecurityContextHolder.getContext().setAuthentication(auth)`.
  7. parseAndValidate에서 예외 던지면: 로그 WARN 후 SecurityContext 비운 상태로 다음 filter 통과 (401은 endpoint 보호 시 처리).
- `shouldNotFilter`: `request.getRequestURI()` 가 `/actuator/`로 시작하면 true (헬스체크에 토큰 불필요).

### 6. SecurityConfig

`src/main/java/com/harness/ticket/global/security/SecurityConfig.java`:

요구사항:
- `@Configuration @EnableWebSecurity`.
- `JwtAuthFilter` 주입.
- `SecurityFilterChain` Bean:
  - `csrf().disable()`
  - `formLogin().disable()`, `httpBasic().disable()`
  - `sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)`
  - `authorizeHttpRequests(auth -> auth.anyRequest().permitAll())` — **이 step에선 모두 허용**. step 1+에서 `/auth/**`는 permit 유지하고 나머지 endpoint 보호로 강화.
  - `addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`
- `RequestIdFilter`(이전 step에서 만든 것)와의 순서: `RequestIdFilter`가 먼저 (HIGHEST_PRECEDENCE), 그 후 Security filter chain에 진입 → 그 안에서 `JwtAuthFilter` 동작. 명시적으로 손 댈 필요 없음.

### 7. ErrorCode 항목 추가

`src/main/java/com/harness/ticket/global/response/ErrorCode.java` enum에 추가 (기존 항목 유지):

```
INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다")
TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "토큰이 만료되었습니다")
REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "REUSE_DETECTED", "재사용된 refresh 토큰이 감지되어 재로그인이 필요합니다")
INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 일치하지 않습니다")
USERNAME_TAKEN(HttpStatus.CONFLICT, "USERNAME_TAKEN", "이미 사용 중인 아이디입니다")
```

> 참고: `INVALID_CREDENTIALS` 메시지는 ID 없음/비번 틀림 모두 동일 — 사용자 enumeration 방지 (ADR-004).

### 8. 테스트

다음 테스트 파일을 작성. 모두 `src/test/java/com/harness/ticket/global/security/`.

#### `JwtProviderTest`
- 정상 access/refresh 발급 → claims 검증 (sub, type, iat, exp).
- access 토큰의 `type=access`, refresh 토큰의 `type=refresh`.
- 만료 토큰(`Clock.fixed`로 과거 시각 발급 후 현재로 검증) → `ExpiredJwtException`.
- 위조 토큰 (서명 부분 변경) → `SignatureException` 또는 `JwtException`.
- secret 32 바이트 미만으로 `JwtProperties` 생성 → `JwtProvider` 인스턴스화 시 `IllegalStateException`.
- `getAccessTtlSec` / `getRefreshTtlSec` 값 정확.

#### `JwtAuthFilterTest`
- `MockMvc` + 더미 컨트롤러(예: `/test/me` → SecurityContext의 userId 반환).
- 시나리오:
  - 헤더 없음 → SecurityContext 비어있음.
  - `Bearer <valid access>` → SecurityContext에 userId 채워짐.
  - `Bearer <expired>` → SecurityContext 비어있음 (예외 안 던지고 통과).
  - `Bearer <refresh token>` → SecurityContext 비어있음 (type 불일치).
  - 잘못된 prefix (`Token xxx`) → SecurityContext 비어있음.
  - `/actuator/health` 요청은 filter 자체가 skip (shouldNotFilter).

#### `SecurityConfigContextTest`
- `@SpringBootTest`로 컨텍스트 로드 검증. `SecurityFilterChain` Bean 존재 + `JwtAuthFilter`가 등록됐는지.

## Acceptance Criteria

```bash
# JWT_SECRET 환경변수 셋팅 (이미 .env 사용 중이면 source)
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "com.harness.ticket.global.security.*"
```

위 명령들이 모두 성공해야 한다. 기존 phase 1-setup 테스트(`HealthIT`, `TicketApplicationTests`, `*GlobalExceptionHandlerTest` 등)도 깨지지 않아야 한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md`의 `global/security/` 디렉토리에 `JwtProvider`, `JwtAuthFilter`, `SecurityConfig`, `JwtProperties` 모두 존재.
   - `/docs/ADR.md` ADR-004 위배 없음 — JWT secret env var, claim 구성, type 분리, Clock 주입 모두 OK.
   - `/CLAUDE.md` CRITICAL 규칙 — 시간은 Clock 주입, PII 로깅 금지, ApiResponse 형식 유지.
3. `phases/2-auth/index.json`의 step 0을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "Spring Security + jjwt 0.12 + JwtProvider(secret 길이 검증, Clock 주입) + JwtAuthFilter(access type만, /actuator skip) + SecurityConfig(STATELESS, permitAll, JwtAuthFilter 등록) + ErrorCode 5종 추가"`
   - 수정 3회 후에도 실패 → `"status": "error"`, `"error_message": "..."`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "..."` 후 즉시 중단

## 금지사항

- `/auth/**` endpoint나 `AuthController`, `AuthService` 만들지 마라. 이유: step 1에서 일관 추가. 본 step은 인프라만.
- application.yml에 `jwt.secret` default 값 박지 마라 (예: `secret: ${JWT_SECRET:some-default}`). 이유: secret이 코드에 박히는 사고 차단. 반드시 env var 강제.
- `JwtAuthFilter`에서 토큰 invalid 시 `response.setStatus(401)` 직접 호출 금지. 이유: 401은 endpoint 보호 시 SecurityContext가 비어있는 상황을 EntryPoint가 처리. 이 step에선 `permitAll`이라 401 자체가 발생하지 않음.
- `User` 엔티티, `UserRepository`, JPA 어떤 도메인 객체도 만들지 마라. 이유: step 1에서 추가.
- `JwtProvider`에서 `Instant.now()` 직접 호출 금지. 이유: ADR-008 — 반드시 `Clock` 주입.
- `BusinessException` 서브클래스(`InvalidTokenException` 등) 만들지 마라. 이유: ErrorCode enum이면 충분.
- 토큰 자체나 secret을 어떤 로그에도 출력 금지. 이유: ADR-009 — 노출 시 즉시 보안 사고.
- 기존 phase 1-setup 테스트를 깨뜨리지 마라.
- `@EnableGlobalMethodSecurity` 또는 `@PreAuthorize` 등 메서드 보안 활성화 금지. 이유: MVP 범위. URL 기반 보호만.
