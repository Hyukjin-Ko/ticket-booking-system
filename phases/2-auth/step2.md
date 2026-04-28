# Step 2: refresh-logout

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙 11개. 특히 PII 로깅 금지, Redis 키 헬퍼.
- `/docs/PRD.md` — API 스타일.
- `/docs/ARCHITECTURE.md` — "인증 시퀀스" 의 refresh / logout 흐름. **Reuse detection 케이스 다이어그램 정확히 이해할 것**.
- `/docs/ADR.md` — **ADR-004 — refresh rotation, reuse detection, logout = DEL refresh:{userId}**. 1 user = 1 refresh 정책.
- 이전 step들에서 만든 파일:
  - `auth/service/AuthService.java` — 메서드 추가할 자리
  - `auth/controller/AuthController.java` — endpoint 추가할 자리
  - `global/security/JwtProvider.java` — `parseAndValidate(String)`, `createAccess`, `createRefresh`, `getAccessTtlSec`, `getRefreshTtlSec`
  - `global/security/SecurityConfig.java` — `/auth/refresh` permitAll 추가, `/auth/logout`은 authenticated
  - `global/redis/RedisKeys.java` — `refresh(Long userId)` 헬퍼
  - `global/response/ErrorCode.java` — `INVALID_TOKEN`, `TOKEN_EXPIRED`, `REUSE_DETECTED`, `UNAUTHORIZED` 이미 있음
  - `auth/dto/LoginResponse.java` — 같은 형태 재사용 또는 RefreshResponse 분리

특히 step 1에서 login 시 어떻게 Redis에 저장했는지 (`opsForValue().set(key, value, Duration.ofSeconds(...))`) 정확히 같은 패턴으로 사용할 것.

## 작업

Refresh rotation, reuse detection, logout.

### 1. DTO

`src/main/java/com/harness/ticket/auth/dto/`:

#### `RefreshRequest.java`
```java
public record RefreshRequest(@NotBlank String refreshToken) {}
```

#### `RefreshResponse.java`
```java
public record RefreshResponse(
    String accessToken,
    String refreshToken,
    long expiresInSec
) {}
```

> `LoginResponse`와 형태가 동일하지만 의미 분리를 위해 별도 record.

### 2. AuthService 메서드 추가

`src/main/java/com/harness/ticket/auth/service/AuthService.java`에 메서드 추가:

```java
@Transactional(readOnly = true)
public RefreshResponse refresh(RefreshRequest req) {
    // 1) JWT 서명·exp 검증 + type=refresh 확인
    Long userId;
    try {
        Jws<Claims> jws = jwtProvider.parseAndValidate(req.refreshToken());
        if (!"refresh".equals(jws.getPayload().get("type", String.class))) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        userId = Long.parseLong(jws.getPayload().getSubject());
    } catch (ExpiredJwtException e) {
        throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
    } catch (JwtException | IllegalArgumentException e) {
        throw new BusinessException(ErrorCode.INVALID_TOKEN);
    }

    // 2) Redis 키 조회 + 일치 검증 (reuse detection)
    String key = RedisKeys.refresh(userId);
    String stored = redisTemplate.opsForValue().get(key);
    if (stored == null || !stored.equals(req.refreshToken())) {
        // 탈취 의심 — 해당 유저 모든 refresh 무효화
        redisTemplate.delete(key);
        log.warn("REUSE_DETECTED userId={}", userId);
        throw new BusinessException(ErrorCode.REUSE_DETECTED);
    }

    // 3) 사용자 존재 검증 (탈퇴된 유저 차단)
    User user = userRepository.findById(userId)
        .orElseThrow(() -> {
            redisTemplate.delete(key);
            return new BusinessException(ErrorCode.INVALID_TOKEN);
        });

    // 4) 새 access + 새 refresh 발급
    String newAccess  = jwtProvider.createAccess(user.getId(), user.getUsername());
    String newRefresh = jwtProvider.createRefresh(user.getId());

    // 5) Redis 덮어쓰기 (rotation)
    redisTemplate.opsForValue().set(
        key, newRefresh,
        Duration.ofSeconds(jwtProvider.getRefreshTtlSec())
    );

    return new RefreshResponse(newAccess, newRefresh, jwtProvider.getAccessTtlSec());
}

public void logout(Long userId) {
    redisTemplate.delete(RedisKeys.refresh(userId));
    log.info("logout userId={}", userId);
}
```

CRITICAL 규칙:
- `refreshToken`을 어떤 로그에도 출력 금지. `userId`만.
- `REUSE_DETECTED` 발동 시 **반드시 `redisTemplate.delete(key)` 호출**. 누락 시 탈취 토큰이 다음 요청에서도 통과 가능.
- 사용자 탈퇴 케이스: refresh token은 valid한데 DB에서 사용자가 삭제됐으면 `INVALID_TOKEN` 반환 + Redis 키 정리.
- access 토큰을 refresh로 잘못 사용하면 `type` claim이 `"access"` 라 step 0 JwtAuthFilter는 거르지만, 여기서도 명시 검증 필요.

### 3. AuthController 메서드 추가

```java
@PostMapping("/refresh")
public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest req) {
    return ApiResponse.success(authService.refresh(req), "토큰이 갱신되었습니다");
}

@PostMapping("/logout")
public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId) {
    authService.logout(userId);
    return ApiResponse.success(null, "로그아웃이 되었습니다");
}
```

> `@AuthenticationPrincipal Long userId`는 step 0 `JwtAuthFilter`가 `UsernamePasswordAuthenticationToken`의 principal로 `userId`(Long)를 넣었기에 가능. 만약 principal 타입이 다르면 step 0 코드를 다시 확인.

### 4. SecurityConfig 갱신

`SecurityConfig`의 `authorizeHttpRequests`를 다음으로:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/auth/signup", "/auth/login", "/auth/refresh").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    .anyRequest().authenticated()
)
```

`/auth/logout`은 authenticated (Bearer access 필수).

### 5. 테스트

#### `auth/service/AuthServiceRefreshTest` (Mockito 단위)
- 정상 rotation:
  - parseAndValidate가 valid claims 반환 (type=refresh, sub=userId).
  - Redis GET → 동일 토큰 반환.
  - userRepository.findById → User 반환.
  - 새 access/refresh 발급 후 Redis SET 호출 확인.
- 만료 refresh → `TOKEN_EXPIRED`.
- 위조 refresh → `INVALID_TOKEN`.
- type=access 토큰을 refresh로 호출 → `INVALID_TOKEN`.
- Redis에 키 없음 (logout 후 재시도) → `REUSE_DETECTED` + `redisTemplate.delete` 호출 확인.
- Redis 값 불일치 (이미 회전됨, 옛 토큰 재시도) → `REUSE_DETECTED` + delete 호출.
- userRepository 빈 Optional → `INVALID_TOKEN` + delete 호출.

#### `auth/service/AuthServiceLogoutTest` (Mockito)
- `logout(userId)` → `redisTemplate.delete(RedisKeys.refresh(userId))` 호출.

#### `auth/controller/AuthRefreshIT` (Testcontainers 통합)
시나리오:
1. **정상 rotation**: signup → login → refresh:
   - 응답 status 200, 새 accessToken/refreshToken (이전과 다름) 검증.
   - Redis의 `refresh:{userId}` 값이 새 refreshToken과 일치.
   - **이전 refreshToken으로 다시 refresh 시도 → 401 + `code:"REUSE_DETECTED"`** + Redis 키 삭제됨 검증.
2. **만료 토큰**: Clock을 미래로 이동시킨 상태에서 발급된 토큰을 현재 clock으로 refresh → 401 + `code:"TOKEN_EXPIRED"`.
3. **위조 토큰**: 토큰 문자열의 마지막 한 글자 변경 후 refresh → 401 + `code:"INVALID_TOKEN"`.
4. **access 토큰을 refresh로**: login 응답의 accessToken을 RefreshRequest에 넣어 refresh → 401 + `code:"INVALID_TOKEN"`.
5. **logout 정상 흐름**: signup → login → access 토큰으로 `POST /auth/logout` → 200 + body `{success:true, code:"OK", message:"로그아웃이 되었습니다"}`. Redis 키 사라짐 검증. 이후 같은 refreshToken으로 refresh → 401 `REUSE_DETECTED`.
6. **logout without token**: Bearer 없이 `POST /auth/logout` → 401 + `code:"UNAUTHORIZED"`.
7. **logout with invalid token**: 위조된 access로 `POST /auth/logout` → 401 + `code:"UNAUTHORIZED"`.

> Clock 조작은 `@TestConfiguration`으로 `Clock` Bean을 `Clock.fixed(...)`로 대체하거나, MutableClock 같은 헬퍼 사용. step 0 ClockConfigTest 패턴 참조.

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "com.harness.ticket.auth.*" --tests "com.harness.ticket.global.security.*"
```

위 명령들이 모두 성공해야 한다. 이전 step들과 phase 1-setup의 테스트도 깨지지 않아야 한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` "인증 시퀀스" 의 refresh / logout 동작과 시나리오 일치.
   - `/docs/ADR.md` ADR-004 — 1 user = 1 refresh, rotation, reuse detection, logout=DEL.
   - `/CLAUDE.md` CRITICAL — Redis 키 헬퍼 사용, PII 로깅 금지, ApiResponse 형식.
3. `phases/2-auth/index.json`의 step 2를 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "AuthService.refresh(rotation + reuse detection + 사용자 탈퇴 가드) + AuthService.logout(DEL) + AuthController(POST /auth/refresh permitAll, /auth/logout authenticated) + 통합 테스트 7 시나리오(정상/만료/위조/access오용/logout 정상/Bearer 없음/위조 access)"`
   - 수정 3회 후에도 실패 → `"status": "error"`, `"error_message": "..."`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "..."` 후 즉시 중단

## 금지사항

- reuse detection 발동 시 `redisTemplate.delete(key)` 누락 금지. 이유: 탈취 토큰이 계속 동작 가능. ADR-004 핵심 보안 보장.
- access 토큰을 refresh로 받아들이지 마라 — `type` claim 명시 검증 필수. 이유: 토큰 분리 라이프사이클.
- `refreshToken` 문자열을 어떤 로그에도 출력 금지. 이유: ADR-009.
- `logout`을 permitAll로 두지 마라 — 반드시 authenticated. 이유: 누구의 refresh를 지울지 식별 불가.
- 탈퇴된 유저(`userRepository.findById` 빈 결과)일 때 새 토큰 발급 금지. Redis 키도 정리. 이유: 좀비 토큰 차단.
- `RefreshResponse`에 필드 추가 금지 — 정확히 `accessToken`, `refreshToken`, `expiresInSec` 3개.
- 멀티 디바이스 지원 시도 금지 (jti 도입, SCAN 등). 이유: ADR-004 — 1 user = 1 refresh 정책. MVP 범위 외.
- access 토큰 만료 시 자동 refresh 시도 같은 서버 측 로직 금지. 이유: 클라이언트 책임.
- 기존 phase 1-setup, step 0/1 테스트 깨뜨리지 마라.
