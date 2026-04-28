package com.harness.ticket.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.harness.ticket.auth.domain.User;
import com.harness.ticket.auth.dto.RefreshRequest;
import com.harness.ticket.auth.dto.RefreshResponse;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.global.security.JwtProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.MalformedJwtException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtProvider, redisTemplate, clock);
    }

    @Test
    void refresh_validToken_rotatesAndStoresNewRefreshInRedis() throws Exception {
        String oldRefresh = "old-refresh-token";
        Long userId = 7L;
        User user = User.create("alice", "$2a$10$encodedHash", clock);
        setId(user, userId);

        Jws<Claims> jws = mockJws("refresh", String.valueOf(userId));
        given(jwtProvider.parseAndValidate(oldRefresh)).willReturn(jws);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.refresh(userId))).willReturn(oldRefresh);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(jwtProvider.createAccess(userId, "alice")).willReturn("new-access");
        given(jwtProvider.createRefresh(userId)).willReturn("new-refresh");
        given(jwtProvider.getAccessTtlSec()).willReturn(900L);
        given(jwtProvider.getRefreshTtlSec()).willReturn(1209600L);

        RefreshResponse res = authService.refresh(new RefreshRequest(oldRefresh));

        assertThat(res.accessToken()).isEqualTo("new-access");
        assertThat(res.refreshToken()).isEqualTo("new-refresh");
        assertThat(res.expiresInSec()).isEqualTo(900L);

        verify(valueOperations, times(1)).set(
                eq(RedisKeys.refresh(userId)),
                eq("new-refresh"),
                eq(Duration.ofSeconds(1209600L))
        );
        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void refresh_expiredToken_throwsTokenExpired() {
        String token = "expired-token";
        given(jwtProvider.parseAndValidate(token)).willThrow(new ExpiredJwtException(null, null, "expired"));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(token)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);

        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void refresh_malformedToken_throwsInvalidToken() {
        String token = "malformed-token";
        given(jwtProvider.parseAndValidate(token)).willThrow(new MalformedJwtException("bad"));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(token)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void refresh_accessTypeToken_throwsInvalidToken() {
        String accessToken = "access-token-misused";
        Jws<Claims> jws = mockJws("access", "7");
        given(jwtProvider.parseAndValidate(accessToken)).willReturn(jws);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(accessToken)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void refresh_redisKeyMissing_throwsReuseDetectedAndDeletes() {
        String token = "valid-but-not-in-redis";
        Long userId = 7L;
        Jws<Claims> jws = mockJws("refresh", String.valueOf(userId));
        given(jwtProvider.parseAndValidate(token)).willReturn(jws);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.refresh(userId))).willReturn(null);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(token)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REUSE_DETECTED);

        verify(redisTemplate, times(1)).delete(RedisKeys.refresh(userId));
    }

    @Test
    void refresh_redisValueMismatch_throwsReuseDetectedAndDeletes() {
        String oldToken = "rotated-out-token";
        Long userId = 7L;
        Jws<Claims> jws = mockJws("refresh", String.valueOf(userId));
        given(jwtProvider.parseAndValidate(oldToken)).willReturn(jws);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.refresh(userId))).willReturn("currently-stored-different-token");

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(oldToken)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REUSE_DETECTED);

        verify(redisTemplate, times(1)).delete(RedisKeys.refresh(userId));
    }

    @Test
    void refresh_userMissing_throwsInvalidTokenAndDeletes() {
        String token = "valid-token";
        Long userId = 99L;
        Jws<Claims> jws = mockJws("refresh", String.valueOf(userId));
        given(jwtProvider.parseAndValidate(token)).willReturn(jws);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.refresh(userId))).willReturn(token);
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(token)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(redisTemplate, times(1)).delete(RedisKeys.refresh(userId));
    }

    @SuppressWarnings("unchecked")
    private Jws<Claims> mockJws(String type, String subject) {
        Jws<Claims> jws = mock(Jws.class);
        Claims claims = mock(Claims.class);
        given(jws.getPayload()).willReturn(claims);
        given(claims.get("type", String.class)).willReturn(type);
        lenient().when(claims.getSubject()).thenReturn(subject);
        return jws;
    }

    private static void setId(User user, Long id) throws Exception {
        Field field = User.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(user, id);
    }
}
