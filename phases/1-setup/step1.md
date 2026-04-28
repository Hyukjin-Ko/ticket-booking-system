# Step 1: common-foundations

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙 11개. 특히 Redis 키 헬퍼, DTO 변환, PII 로깅 금지, `@Valid` 강제.
- `/docs/PRD.md` — "API 스타일" 섹션의 응답 포맷 (`{ success, code, message, data }`).
- `/docs/ARCHITECTURE.md` — 디렉토리 구조의 `global/` 하위 패키지(`response/`, `exception/`, `redis/`, `logging/`)와 "컨벤션" 섹션의 입력 검증·로깅·시간.
- `/docs/ADR.md` — ADR-009(로깅), ADR-008(시간/Clock).
- 이전 step에서 생성된 파일:
  - `build.gradle` (의존성 확인)
  - `src/main/java/com/harness/ticket/TicketApplication.java`
  - `src/main/java/com/harness/ticket/global/config/ClockConfig.java`
  - `src/main/resources/application.yml` (logging.pattern.level의 `[%X{requestId}]` 부분)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 패키지 구조와 설정값을 이해한 뒤 작업하라.

## 작업

전 도메인이 공유할 응답·예외·로깅·Redis 키·필터를 만든다. 도메인-specific 코드는 만들지 않는다.

### 1. ApiResponse — 공통 응답 래퍼

`src/main/java/com/harness/ticket/global/response/ApiResponse.java`

요구사항:
- 제네릭 `<T>`.
- 필드: `boolean success`, `String code`, `String message`, `T data`.
- `@Getter` + `@Builder` (Lombok).
- `@JsonInclude(JsonInclude.Include.NON_NULL)` — null `data` 직렬화 시 생략.
- 정적 팩토리 메서드:
  - `static <T> ApiResponse<T> success(T data)` — `success=true, code="OK", message="성공"`
  - `static <T> ApiResponse<T> success(T data, String message)` — message override
  - `static <T> ApiResponse<T> error(ErrorCode errorCode)` — `success=false`, code/message는 ErrorCode에서.
  - `static <T> ApiResponse<T> error(ErrorCode errorCode, T data)` — error 응답에 data(예: 검증 실패 필드 목록).

### 2. ErrorCode — 에러 코드 enum

`src/main/java/com/harness/ticket/global/response/ErrorCode.java`

- enum + 필드 `(HttpStatus httpStatus, String code, String defaultMessage)`.
- `@Getter` + `@RequiredArgsConstructor`.
- 본 step에서 정의할 enum 값 (도메인 횡단만):
  - `INVALID_REQUEST(BAD_REQUEST, "INVALID_REQUEST", "요청이 올바르지 않습니다")`
  - `UNAUTHORIZED(UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다")`
  - `FORBIDDEN(FORBIDDEN, "FORBIDDEN", "권한이 없습니다")`
  - `NOT_FOUND(NOT_FOUND, "NOT_FOUND", "리소스를 찾을 수 없습니다")`
  - `CONFLICT(CONFLICT, "CONFLICT", "요청이 충돌합니다")`
  - `INTERNAL_ERROR(INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다")`

도메인-specific 코드(`SEAT_ALREADY_HELD`, `RESERVATION_CONFLICT` 등)는 본 step에서 추가하지 마라 — 해당 phase에서 추가한다.

### 3. BusinessException

`src/main/java/com/harness/ticket/global/exception/BusinessException.java`

- `extends RuntimeException`
- 필드 `ErrorCode errorCode`, `@Getter`.
- 생성자 2개:
  - `BusinessException(ErrorCode errorCode)` — message는 `errorCode.getDefaultMessage()`
  - `BusinessException(ErrorCode errorCode, String message)` — message override
- 서브클래스 만들지 마라 (CLAUDE.md 컨벤션).

### 4. GlobalExceptionHandler

`src/main/java/com/harness/ticket/global/exception/GlobalExceptionHandler.java`

`@RestControllerAdvice` + `@Slf4j` (Lombok). 다음 핸들러를 등록:

| 예외 | 응답 ErrorCode | HTTP | 로그 레벨 |
|---|---|---|---|
| `BusinessException` | `e.getErrorCode()` | ErrorCode의 httpStatus | WARN (`log.warn("BusinessException: code={}, message={}", code, message)`) |
| `MethodArgumentNotValidException` | `INVALID_REQUEST` | 400 | WARN. data에 필드 에러 목록 포함 — `[{field, reason}, ...]` |
| `IllegalStateException` | `CONFLICT` | 409 | WARN. (도메인 상태머신 위반에 사용) |
| `org.springframework.orm.ObjectOptimisticLockingFailureException` | `CONFLICT` | 409 | WARN. (낙관락 충돌, phase 3에서 발생 예정이지만 미리 등록) |
| `Exception` (fallback) | `INTERNAL_ERROR` | 500 | ERROR + stack trace (`log.error("unhandled", e)`). 사용자 응답 message는 generic만 |

CRITICAL 규칙:
- 어떤 응답에도 stack trace, 비밀번호, 토큰 노출 금지.
- 5xx fallback의 사용자 응답은 항상 `INTERNAL_ERROR.defaultMessage` (= `"서버 오류가 발생했습니다"`).
- `printStackTrace()` 절대 금지. SLF4J `log.error(msg, e)` 만.

응답 빌드는 `ApiResponse.error(errorCode)` 또는 `ApiResponse.error(errorCode, dataMap)` 사용. HTTP 상태는 `ResponseEntity.status(errorCode.getHttpStatus()).body(...)` 로 매핑.

### 5. RedisKeys — Redis 키 헬퍼

`src/main/java/com/harness/ticket/global/redis/RedisKeys.java`

- `final class` + Lombok `@NoArgsConstructor(access = AccessLevel.PRIVATE)`.
- 모든 메서드 `public static String`.
- 시그니처:
  ```java
  static String seat(Long showId, Long seatId)        // "seat:{showId}:{seatId}"
  static String count(Long userId, Long showId)       // "count:user:{userId}:{showId}"
  static String queue(Long showId)                    // "queue:{showId}"
  static String admit(Long showId)                    // "admit:{showId}"
  static String refresh(Long userId)                  // "refresh:{userId}"
  ```
- 구현은 `String.format` 또는 단순 concat. null id 받으면 `IllegalArgumentException` 던져라 (`Objects.requireNonNull`).

### 6. RequestIdFilter — MDC 주입

`src/main/java/com/harness/ticket/global/logging/RequestIdFilter.java`

- `extends OncePerRequestFilter`, `@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE)`.
- 동작:
  1. 요청 진입 시 `MDC.put("requestId", UUID.randomUUID().toString())`.
  2. 응답 헤더 `X-Request-Id`에 같은 값 추가.
  3. `chain.doFilter(req, res)` 호출.
  4. `finally` 블록에서 `MDC.clear()`.
- 클라이언트가 헤더 `X-Request-Id`를 보내면 그 값을 우선 사용 (있으면 그대로, 없으면 새 UUID). 분산 추적 호환.

### 7. 테스트

다음 5개 테스트 파일을 작성. 모두 `src/test/java/com/harness/ticket/global/...` 하위.

#### `response/ApiResponseTest`
- `success(data)` 호출 → success=true, code="OK", data=주어진 값.
- `error(ErrorCode.INVALID_REQUEST)` → success=false, code="INVALID_REQUEST", data=null.
- Jackson으로 직렬화 → null `data` 필드가 JSON에 포함되지 않음.

#### `response/ErrorCodeTest`
- 모든 enum 값에 대해 `httpStatus`, `code`, `defaultMessage` 가 적절히 매핑되었는지.

#### `redis/RedisKeysTest`
- `seat(1L, 2L)` → `"seat:1:2"`
- `count(42L, 7L)` → `"count:user:42:7"`
- `queue(5L)` → `"queue:5"`
- `admit(5L)` → `"admit:5"`
- `refresh(42L)` → `"refresh:42"`
- null 인자 시 `NullPointerException` 또는 `IllegalArgumentException`.

#### `exception/GlobalExceptionHandlerTest`
- `@WebMvcTest` 로 더미 컨트롤러 (`/test/...`) 와 핸들러만 로드.
- 각 시나리오:
  - `BusinessException(NOT_FOUND)` 던지는 endpoint → 404 + body `{success:false, code:"NOT_FOUND", ...}`.
  - `MethodArgumentNotValidException` 유발 (`@Valid` 위반) → 400 + body의 `data.errors` 포함.
  - `IllegalStateException` → 409.
  - `ObjectOptimisticLockingFailureException` → 409.
  - 예상 못한 `RuntimeException` → 500 + 사용자 응답 message는 generic.

#### `logging/RequestIdFilterTest`
- `@WebMvcTest` 또는 `MockMvc`. 요청 한 번 보내고:
  - 응답에 `X-Request-Id` 헤더 존재.
  - MDC가 요청 후 비어있는지 (다음 요청에서 누수 없는지).
- 클라이언트가 `X-Request-Id` 보내면 그 값이 그대로 응답에도 들어가는지.

## Acceptance Criteria

```bash
./gradlew test --tests "com.harness.ticket.global.*"
./gradlew build
```

위 두 명령이 모두 성공해야 한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md`의 디렉토리 구조와 일치 (`global/response/`, `global/exception/`, `global/redis/`, `global/logging/`).
   - `/docs/ADR.md` ADR-009(로깅)에 위배되지 않음 — PII 노출 없는지 응답·로그 메시지 모두 확인.
   - `/CLAUDE.md` CRITICAL 규칙(특히 Redis 키 헬퍼, DTO 응답, PII 금지, `@Valid`) 위반 없음.
3. 결과에 따라 `phases/1-setup/index.json`의 step 1을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "ApiResponse({success,code,message,data}) + ErrorCode enum + BusinessException + GlobalExceptionHandler(낙관락 포함) + RedisKeys 5종 + RequestIdFilter MDC 구축"`
   - 수정 3회 후에도 실패 → `"status": "error"`, `"error_message": "..."`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "..."` 후 중단

## 금지사항

- 도메인-specific ErrorCode 추가 금지 (`SEAT_ALREADY_HELD`, `RESERVATION_CONFLICT`, `USERNAME_TAKEN`, `INVALID_CREDENTIALS` 등). 이유: 해당 phase에서 추가가 자연스럽고 도메인 경계가 명확해진다.
- `BusinessException` 서브클래스 만들지 마라. 이유: ErrorCode enum이 변형의 자리. 예외 클래스 폭발 방지.
- `printStackTrace()` 또는 `e.printStackTrace()` 사용 금지. 이유: ADR-009 — SLF4J만.
- 응답·로그 어디에도 비밀번호, JWT 토큰, BCrypt 해시 노출 금지. 이유: CRITICAL 규칙.
- `@Data`, `@AllArgsConstructor` 무분별 사용 금지. 필요한 어노테이션만(`@Getter`, `@Builder`, `@RequiredArgsConstructor`, `@NoArgsConstructor(access=PRIVATE)`).
- `RedisTemplate` 직접 호출 금지 (이번 step에는 Redis 호출 없음). 이유: 본 step은 키 헬퍼만 만든다. 실제 사용은 도메인 phase에서.
- 기존 step 0의 테스트(`TicketApplicationTests`, `HealthIT`, `ClockConfigTest`)를 깨뜨리지 마라.
- `ApiResponse`에 필드 추가 금지 — 정확히 `success`, `code`, `message`, `data` 4개.
