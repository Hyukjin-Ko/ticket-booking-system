# Step 0: concert-seat-domain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/CLAUDE.md` — CRITICAL 규칙 11개. 특히 `@Setter` 금지, DTO 응답, `@Valid`, Clock 주입.
- `/docs/PRD.md` — 핵심 기능 2번(공연 목록 및 좌석 조회) + API 스타일.
- `/docs/ARCHITECTURE.md` — `concert/` 패키지 구조, 페이지네이션 컨벤션.
- 이전 phase 산출물:
  - `auth/domain/User.java` — 엔티티 작성 패턴 참조 (`@Getter`, `@NoArgsConstructor(access=PROTECTED)`, 정적 팩토리, `Clock` 주입)
  - `global/security/SecurityConfig.java` — `/concerts/**` authenticated 추가할 자리
  - `global/security/JwtAuthFilter.java:55` — principal로 `Long userId` 주입 확인
  - `global/response/ApiResponse.java` — 응답 포맷
  - `src/main/resources/db/migration/` — V1, V2 존재. V3, V4 추가
  - `src/test/java/com/harness/ticket/support/IntegrationTestSupport.java` — Testcontainers 베이스

User 엔티티가 Clock 주입으로 `createdAt`을 세팅하는 패턴을 정확히 똑같이 따라가라.

## 작업

Concert/Seat 도메인 + 조회 API + seed 데이터. 예매 로직은 step 1+에서.

### 1. Flyway V3 — 도메인 테이블

`src/main/resources/db/migration/V3__concert_seat.sql`:

```sql
CREATE TABLE concert (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200)  NOT NULL,
    starts_at       TIMESTAMPTZ   NOT NULL,
    queue_enabled   BOOLEAN       NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE seat (
    id          BIGSERIAL    PRIMARY KEY,
    concert_id  BIGINT       NOT NULL REFERENCES concert(id) ON DELETE CASCADE,
    section     VARCHAR(20)  NOT NULL,
    row_no      INT          NOT NULL,
    col_no      INT          NOT NULL,
    UNIQUE (concert_id, section, row_no, col_no)
);

CREATE INDEX idx_seat_concert ON seat (concert_id);
```

### 2. Flyway V4 — Seed 데이터 (1개 공연 100석)

`src/main/resources/db/migration/V4__seed_concert.sql`:

```sql
INSERT INTO concert (title, starts_at, queue_enabled)
VALUES ('하네스 페스티벌 2026', NOW() + INTERVAL '30 days', false);

-- 100석: section 'A', row 1~10, col 1~10
INSERT INTO seat (concert_id, section, row_no, col_no)
SELECT 1, 'A', r.row_no, c.col_no
FROM generate_series(1, 10) AS r(row_no)
CROSS JOIN generate_series(1, 10) AS c(col_no);
```

> seed는 Flyway에 두는 게 가장 단순. 추후 dev/prod 분리 필요 시 별도 시드 전략 고민. MVP는 모든 환경에서 동일 seed.

### 3. Concert / Seat 엔티티

`src/main/java/com/harness/ticket/concert/domain/Concert.java`:

요구사항:
- `@Entity @Table(name="concert")`, `@Getter`, `@NoArgsConstructor(access=PROTECTED)`. **`@Setter` 금지**.
- 필드: `Long id` (IDENTITY), `String title`, `Instant startsAt`, `boolean queueEnabled`, `Instant createdAt`.
- 정적 팩토리 `Concert.create(String title, Instant startsAt, boolean queueEnabled, Clock clock)`. createdAt은 `Instant.now(clock)`.
- `@PrePersist` 사용 금지.

`src/main/java/com/harness/ticket/concert/domain/Seat.java`:

- `@Entity @Table(name="seat")`. 같은 패턴.
- 필드: `Long id`, `Long concertId`, `String section`, `int rowNo`, `int colNo`.
- 정적 팩토리 `Seat.create(Long concertId, String section, int rowNo, int colNo)`.
- **`Concert`와 `@ManyToOne` 매핑 사용 금지**. concertId Long 필드만. 이유: 좌석 조회 시 N+1 회피, 단순성.

### 4. Repository

`src/main/java/com/harness/ticket/concert/repository/ConcertRepository.java`:

```java
public interface ConcertRepository extends JpaRepository<Concert, Long> {}
```

`src/main/java/com/harness/ticket/concert/repository/SeatRepository.java`:

```java
public interface SeatRepository extends JpaRepository<Seat, Long> {
    Page<Seat> findByConcertId(Long concertId, Pageable pageable);
    Optional<Seat> findByIdAndConcertId(Long id, Long concertId);
}
```

> `findByIdAndConcertId`는 step 1 (선점)에서 좌석 존재·소속 검증에 사용.

### 5. DTO

`src/main/java/com/harness/ticket/concert/dto/`:

#### `ConcertResponse.java`
```java
public record ConcertResponse(
    Long id,
    String title,
    Instant startsAt,
    boolean queueEnabled
) {
    public static ConcertResponse from(Concert c) {
        return new ConcertResponse(c.getId(), c.getTitle(), c.getStartsAt(), c.isQueueEnabled());
    }
}
```

#### `SeatResponse.java`
```java
public record SeatResponse(Long id, String section, int rowNo, int colNo) {
    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getSection(), s.getRowNo(), s.getColNo());
    }
}
```

#### `PageResponse.java` (공통 페이지 변환)
공통이므로 `global/response/PageResponse.java`에 둔다:
```java
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
```

### 6. Service

`src/main/java/com/harness/ticket/concert/service/ConcertQueryService.java`:

```java
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ConcertQueryService {
    private final ConcertRepository concertRepository;
    private final SeatRepository seatRepository;

    public PageResponse<ConcertResponse> findAll(Pageable pageable) {
        Page<ConcertResponse> p = concertRepository.findAll(pageable).map(ConcertResponse::from);
        return PageResponse.from(p);
    }

    public PageResponse<SeatResponse> findSeats(Long concertId, Pageable pageable) {
        if (!concertRepository.existsById(concertId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다");
        }
        Page<SeatResponse> p = seatRepository.findByConcertId(concertId, pageable).map(SeatResponse::from);
        return PageResponse.from(p);
    }
}
```

### 7. Controller

`src/main/java/com/harness/ticket/concert/controller/ConcertController.java`:

```java
@RestController @RequestMapping("/concerts") @RequiredArgsConstructor
public class ConcertController {
    private final ConcertQueryService concertQueryService;

    @GetMapping
    public ApiResponse<PageResponse<ConcertResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        validateSize(pageable);
        return ApiResponse.success(concertQueryService.findAll(pageable));
    }

    @GetMapping("/{concertId}/seats")
    public ApiResponse<PageResponse<SeatResponse>> seats(
            @PathVariable Long concertId,
            @PageableDefault(size = 20) Pageable pageable) {
        validateSize(pageable);
        return ApiResponse.success(concertQueryService.findSeats(concertId, pageable));
    }

    private void validateSize(Pageable pageable) {
        if (pageable.getPageSize() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "페이지 크기는 100을 넘을 수 없습니다");
        }
    }
}
```

### 8. SecurityConfig 갱신

`SecurityConfig`의 `authorizeHttpRequests`를:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/auth/signup", "/auth/login", "/auth/refresh").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    .anyRequest().authenticated()
)
```

> `/concerts/**`는 authenticated 정책에 자동 포함됨 (`anyRequest().authenticated()`).

### 9. 테스트

#### `concert/domain/ConcertTest`, `SeatTest` (POJO 단위)
- 정적 팩토리로 생성 → 필드 정상 세팅, createdAt = clock 시각.

#### `concert/service/ConcertQueryServiceTest` (Mockito)
- `findAll`: pageable 전달 → repository 호출, DTO 변환, PageResponse 반환.
- `findSeats`: 존재하지 않는 concertId → `NOT_FOUND`.
- `findSeats`: 정상 → SeatResponse 페이지 반환.

#### `concert/controller/ConcertControllerIT` (Testcontainers 통합)
- `IntegrationTestSupport` 베이스. 사전: signup + login으로 access 토큰 획득.
- 시나리오:
  1. 토큰 없이 `GET /concerts` → 401 `UNAUTHORIZED`.
  2. valid 토큰 + `GET /concerts` → 200, body의 `data.content`에 seed 공연 1개 포함, `data.totalElements >= 1`.
  3. valid 토큰 + `GET /concerts/1/seats?size=20` → 200, `data.content` 20개 좌석, `data.totalElements=100`, `data.totalPages=5`.
  4. valid 토큰 + `GET /concerts/999/seats` → 404 `NOT_FOUND`.
  5. valid 토큰 + `GET /concerts?size=200` → 400 `INVALID_REQUEST` (size 100 초과).

## Acceptance Criteria

```bash
set -a && source .env && set +a

./gradlew build
./gradlew test --tests "com.harness.ticket.concert.*"
```

위 명령들이 성공해야 한다. 이전 phase의 모든 테스트(`*Auth*`, `*Jwt*`, `*Health*` 등)도 깨지지 않아야 한다.

## 검증 절차

1. 위 AC 커맨드 실행.
2. 아키텍처 체크리스트:
   - `/docs/ARCHITECTURE.md` 디렉토리 구조의 `concert/` 패키지 + `global/response/PageResponse` 위치 일치.
   - `/CLAUDE.md` CRITICAL — `@Setter` 없음, DTO 응답, Clock 주입, `@Valid` (이 step에선 검증 대상 body 없으나 `Pageable` 사이즈 검증 포함).
   - 페이지네이션 정책 (size 기본 20, max 100).
3. `phases/3-reservation/index.json`의 step 0을 업데이트:
   - 성공 → `"status": "completed"`, `"summary": "Concert/Seat 엔티티(@Setter 없음, Clock 주입) + V3 스키마 + V4 seed(공연1·좌석100) + ConcertQueryService + GET /concerts·/concerts/{id}/seats(authenticated, Pageable size≤100) + PageResponse 공통 DTO"`
   - 실패/중단 시 error/blocked 처리.

## 금지사항

- `Concert`-`Seat` 사이 JPA `@ManyToOne` 또는 `@OneToMany` 매핑 금지. 이유: N+1 회피·단순성. concertId Long 필드만.
- `@Setter` 추가 금지.
- `@PrePersist`로 `createdAt` 자동 세팅 금지. 이유: Clock 일관성.
- 좌석 등록·수정 API 만들지 마라. 이유: MVP — 공연/좌석은 seed 주입만.
- `Reservation` 엔티티, 예매 관련 코드 만들지 마라. 이유: step 1에서 일관 추가.
- `Pageable`의 size 검증 누락 금지 — 100 초과 시 400. 이유: ARCHITECTURE 페이지네이션 정책.
- 응답에 엔티티 직접 노출 금지. 항상 `XxxResponse` 변환.
- `Instant.now()` 직접 호출 금지.
- 기존 phase 1-setup, 2-auth 테스트를 깨뜨리지 마라.
