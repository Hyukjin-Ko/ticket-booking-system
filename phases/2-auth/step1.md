# Step 1: signup-login

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙 11개. 특히 DTO 변환, `@Valid` 강제, Clock 주입, PII 로깅 금지, Redis 키 헬퍼.
- `/docs/PRD.md` — API 스타일.
- `/docs/ARCHITECTURE.md` — "인증 시퀀스" 섹션 (signup → login 흐름), 패키지 구조의 `auth/` 하위 (`controller/`, `service/`, `domain/`, `repository/`, `dto/`).
- `/docs/ADR.md` — **ADR-004** (비밀번호 정책 8자, BCrypt strength 10, 로그인 메시지 통일, refresh:{userId} 단일 키), ADR-008(Clock).
- 이전 step들에서 만든 파일:
  - `src/main/java/com/harness/ticket/global/security/JwtProvider.java` — `createAccess`, `createRefresh`, `getAccessTtlSec`
  - `src/main/java/com/harness/ticket/global/security/SecurityConfig.java` — `/auth/signup`, `/auth/login`을 permitAll로 유지할 자리
  - `src/main/java/com/harness/ticket/global/redis/RedisKeys.java` — `refresh(userId)` 헬퍼
  - `src/main/java/com/harness/ticket/global/response/ErrorCode.java` — `USERNAME_TAKEN`, `INVALID_CREDENTIALS` 이미 추가됨
  - `src/main/java/com/harness/ticket/global/exception/BusinessException.java` / `GlobalExceptionHandler.java`
  - `src/main/resources/db/migration/V1__baseline.sql` — V2 마이그레이션을 그 다음 버전으로 추가

이전 step에서 `JwtProvider`가 어떻게 토큰을 발급하는지, `SecurityConfig`의 현재 상태를 정확히 이해한 뒤 작업하라.

## 작업

User 도메인 + 회원가입 + 로그인 + 토큰 발급 + Redis refresh 저장.

### 1. Flyway 마이그레이션 V2

`src/main/resources/db/migration/V2__user.sql`:

```sql
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(60)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_username ON users (username);
```

> 한글 username 허용을 위해 컬럼은 `VARCHAR(50)` (Postgres는 character 단위라 한글 OK). UNIQUE 제약으로 중복 방지.

### 2. User 엔티티

`src/main/java/com/harness/ticket/auth/domain/User.java`:

요구사항:
- `@Entity`, `@Table(name = "users")`, JPA.
- 필드:
  - `Long id` (`@Id @GeneratedValue(strategy = IDENTITY)`)
  - `String username` (`@Column(nullable=false, unique=true, length=50)`)
  - `String passwordHash` (`@Column(nullable=false, length=60)`) — BCrypt 결과 길이 60
  - `Instant createdAt` (`@Column(nullable=false, updatable=false)`)
- Lombok: `@Getter`, `@NoArgsConstructor(access = PROTECTED)` (JPA 요구). `@Setter` 금지.
- 정적 팩토리 `User.create(String username, String passwordHash, Clock clock)` — `createdAt`은 `Instant.now(clock)` 으로 세팅.
- `@PrePersist` 사용 금지 — 명시적 팩토리로만 생성.

### 3. UserRepository

`src/main/java/com/harness/ticket/auth/repository/UserRepository.java`:

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

### 4. PasswordEncoder Bean

`src/main/java/com/harness/ticket/global/config/PasswordEncoderConfig.java`:

```java
@Configuration
public class PasswordEncoderConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);   // ADR-004
    }
}
```

### 5. DTO

`src/main/java/com/harness/ticket/auth/dto/`:

#### `SignupRequest.java`
```java
public record SignupRequest(
    @NotBlank
    @Size(min = 4, max = 50)
    @Pattern(regexp = "^[가-힣A-Za-z0-9]+$", message = "한글, 영문, 숫자만 사용 가능합니다")
    String username,

    @NotBlank
    @Size(min = 8, max = 50)
    @Pattern(regexp = "^[A-Za-z0-9]+$", message = "비밀번호는 영문/숫자만 가능합니다")
    String password
) {}
```

#### `SignupResponse.java`
```java
public record SignupResponse(Long userId, String username) {}
```

#### `LoginRequest.java`
```java
public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {}
```

#### `LoginResponse.java`
```java
public record LoginResponse(
    String accessToken,
    String refreshToken,
    long expiresInSec
) {}
```

> `expiresInSec`은 access 토큰 기준 (= `accessTtlMinutes * 60`).

### 6. AuthService

`src/main/java/com/harness/ticket/auth/service/AuthService.java`:

요구사항:
- `@Service @RequiredArgsConstructor @Slf4j`.
- 주입: `UserRepository`, `PasswordEncoder`, `JwtProvider`, `StringRedisTemplate`, `Clock`.
- 메서드:

```java
@Transactional
public SignupResponse signup(SignupRequest req) {
    // 1) 중복 검사
    if (userRepository.existsByUsername(req.username())) {
        throw new BusinessException(ErrorCode.USERNAME_TAKEN);
    }
    // 2) 해시 후 저장
    String hash = passwordEncoder.encode(req.password());
    User saved = userRepository.save(User.create(req.username(), hash, clock));
    // 3) 응답
    return new SignupResponse(saved.getId(), saved.getUsername());
}

@Transactional(readOnly = true)
public LoginResponse login(LoginRequest req) {
    // 1) 조회 + 비번 검증 — 어느 단계든 실패 시 동일 에러 (enumeration 방지)
    User user = userRepository.findByUsername(req.username())
        .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
    // 2) 토큰 발급
    String access  = jwtProvider.createAccess(user.getId(), user.getUsername());
    String refresh = jwtProvider.createRefresh(user.getId());
    // 3) refresh를 Redis에 저장 (덮어쓰기, TTL = refresh-ttl-days)
    redisTemplate.opsForValue().set(
        RedisKeys.refresh(user.getId()),
        refresh,
        Duration.ofSeconds(jwtProvider.getRefreshTtlSec())
    );
    return new LoginResponse(access, refresh, jwtProvider.getAccessTtlSec());
}
```

CRITICAL 규칙:
- 로그인 실패 응답 메시지를 다르게 만들지 마라 (`USER_NOT_FOUND`, `WRONG_PASSWORD` 같은 분기 금지). `INVALID_CREDENTIALS` 단일.
- `log.info` 등 어떤 로그에도 비밀번호·토큰·해시 출력 금지. 사용자 식별은 `userId` 만.
- Redis SET은 `Duration.ofSeconds`로 TTL 명시. TTL 없는 SET 금지 (refresh 영구 잔존 위험).
- `@Transactional`은 클래스 또는 메서드. login은 `readOnly=true` (DB write 없음 — Redis는 트랜잭션 외).

### 7. AuthController

`src/main/java/com/harness/ticket/auth/controller/AuthController.java`:

```java
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest req) {
        SignupResponse res = authService.signup(req);
        return ResponseEntity.status(201).body(ApiResponse.success(res, "회원가입이 완료되었습니다"));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.success(authService.login(req), "로그인 되었습니다");
    }
}
```

### 8. SecurityConfig 업데이트

`SecurityConfig`의 `authorizeHttpRequests`를 다음으로 강화:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/auth/signup", "/auth/login").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    .anyRequest().authenticated()
)
```

이제 `/auth/signup`, `/auth/login`, `/actuator/**` 외 모든 경로는 valid access 토큰 필요. step 2에서 `/auth/refresh`도 permitAll에 추가, `/auth/logout`은 authenticated로 둘 예정.

추가로 `AuthenticationEntryPoint`를 등록 — 인증 실패(SecurityContext 비어있음 + authenticated endpoint 접근) 시 401 + `ApiResponse(success=false, code=UNAUTHORIZED)` 반환:

```java
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;
    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        res.setStatus(401);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(res.getWriter(), ApiResponse.error(ErrorCode.UNAUTHORIZED));
    }
}
```

`SecurityConfig`에 `.exceptionHandling(eh -> eh.authenticationEntryPoint(jwtAuthenticationEntryPoint))` 추가.

### 9. 테스트

#### `auth/domain/UserTest`
- `User.create(username, hash, clock)` 호출 → 필드 채워짐, `createdAt` = clock의 시각.
- POJO 단위 테스트 (DB 없이).

#### `auth/service/AuthServiceTest` (Mockito 단위)
- signup: 기존 username → `USERNAME_TAKEN` 발생.
- signup: 정상 → `userRepository.save` 호출됨, password가 해시되어 저장, 응답 DTO 정확.
- login: 사용자 없음 → `INVALID_CREDENTIALS`.
- login: 사용자 있음 + 비번 불일치 → `INVALID_CREDENTIALS` (동일 메시지 검증).
- login: 정상 → access/refresh 발급, `redisTemplate.opsForValue().set(...)` 호출됨, TTL 정확.

#### `auth/controller/AuthControllerIT` (Testcontainers 통합)
- `IntegrationTestSupport` 베이스 클래스 사용.
- 시나리오:
  1. `POST /auth/signup` 정상 → 201 + body `{success:true, code:"OK", data:{userId, username}, message:"회원가입이 완료되었습니다"}`. DB에 `users` row 생성됨, `password_hash`는 평문과 다름(BCrypt).
  2. 같은 username 재가입 → 409 + `code:"USERNAME_TAKEN"`.
  3. `username` 형식 위배(특수문자 `!@#`) → 400 + `code:"INVALID_REQUEST"`, `data.errors`에 `username` 필드 포함.
  4. `password` 길이 7 → 400 + `data.errors`에 `password` 포함.
  5. 한글 username (`홍길동1`) 정상 가입 → 201.
  6. `POST /auth/login` 정상 → 200 + `{accessToken, refreshToken, expiresInSec=900}`. Redis에 `refresh:{userId}` 키 존재 + 값이 응답의 refreshToken과 동일.
  7. 잘못된 비번 → 401 + `code:"INVALID_CREDENTIALS"`.
  8. 존재하지 않는 username → 401 + `code:"INVALID_CREDENTIALS"` (동일 메시지).
  9. 보호된 endpoint(예: `/test/protected` 더미 컨트롤러를 테스트 전용으로 만들어 검증 또는 임의 endpoint) — 토큰 없이 호출 → 401 + `code:"UNAUTHORIZED"`. 본 step에선 `/auth/**`만 만들었으니 다른 endpoint는 없으므로 이 검증은 step 2 이후로 미뤄도 됨.

> 통합 테스트에서 토큰 만료 시간 등은 `Clock` Bean을 `@MockBean` 또는 `@TestConfiguration`으로 fixed clock 주입해 결정성 확보.

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "com.harness.ticket.auth.*" --tests "com.harness.ticket.global.security.*"
```

위 명령들이 모두 성공해야 한다. 기존 step 0의 테스트와 phase 1-setup 테스트도 깨지지 않아야 한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` "인증 시퀀스"의 signup/login 동작과 일치하는가?
   - `/docs/ADR.md` ADR-004 모든 항목 준수 (BCrypt 10, 8자 정책, 메시지 통일, refresh:{userId} 단일 키, TTL).
   - `/CLAUDE.md` CRITICAL 규칙 — `@Valid` 강제, DTO 응답, PII 금지, Clock 주입, Redis 키 헬퍼 사용.
3. `phases/2-auth/index.json`의 step 1을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "User 엔티티(@Setter 없음) + Flyway V2 + BCrypt 10 + AuthService(signup/login, INVALID_CREDENTIALS 통일) + AuthController(POST /auth/signup·login) + JwtAuthenticationEntryPoint(401 ApiResponse) + 한글 username 허용"`
   - 수정 3회 후에도 실패 → `"status": "error"`, `"error_message": "..."`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "..."` 후 즉시 중단

## 금지사항

- `User` 엔티티에 `@Setter` 추가 금지. 이유: 의도하지 않은 변경 차단. 모든 생성은 `User.create` 정적 팩토리.
- 로그인 실패 메시지를 ID 없음 / 비번 틀림으로 분기 금지. 이유: ADR-004 — 사용자 enumeration 방지.
- 비밀번호 정책 완화 금지 (`@Size(min=6)` 등). 이유: ADR-004 — 최소 8자.
- BCrypt strength 변경 금지 (10이 아닌 값). 이유: ADR-004.
- `AuthService.login`에서 password를 어떤 로그에도 출력 금지. 토큰도 마찬가지. `log.info("login userId={}", userId)` 까지만 허용.
- Redis SET 시 TTL 누락 금지. 이유: refresh 영구 잔존 = 메모리 누수 + 보안 위험.
- `/auth/refresh`, `/auth/logout` endpoint 만들지 마라. 이유: step 2에서 일관 추가.
- `User` 엔티티를 직접 응답 금지 — 반드시 `SignupResponse` 변환. 이유: CLAUDE.md CRITICAL.
- `Instant.now()` 직접 호출 금지 — `Clock` 주입.
- `printStackTrace()` 금지.
- `@PrePersist`로 `createdAt` 자동 세팅 금지. 이유: Clock 주입 일관성. `User.create`에서 명시적 세팅.
- 기존 phase 1-setup, step 0 테스트를 깨뜨리지 마라.
