package com.harness.ticket.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.harness.ticket.auth.domain.User;
import com.harness.ticket.auth.dto.LoginRequest;
import com.harness.ticket.auth.dto.LoginResponse;
import com.harness.ticket.auth.dto.SignupRequest;
import com.harness.ticket.auth.dto.SignupResponse;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.global.security.JwtProvider;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
    void signup_duplicateUsername_throwsUsernameTaken() {
        given(userRepository.existsByUsername("alice")).willReturn(true);

        assertThatThrownBy(() -> authService.signup(new SignupRequest("alice", "password123")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USERNAME_TAKEN);

        verify(userRepository, never()).save(any());
    }

    @Test
    void signup_success_savesEncodedPasswordAndReturnsResponse() throws Exception {
        given(userRepository.existsByUsername("alice")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("$2a$10$encodedHash");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User u = invocation.getArgument(0);
            setId(u, 42L);
            return u;
        });

        SignupResponse res = authService.signup(new SignupRequest("alice", "password123"));

        assertThat(res.userId()).isEqualTo(42L);
        assertThat(res.username()).isEqualTo("alice");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$encodedHash");
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-04-28T12:00:00Z"));
    }

    @Test
    void login_userNotFound_throwsInvalidCredentials() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "password123")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void login_passwordMismatch_throwsInvalidCredentials() throws Exception {
        User user = User.create("alice", "$2a$10$encodedHash", clock);
        setId(user, 7L);
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong-password", "$2a$10$encodedHash")).willReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void login_success_issuesTokensAndStoresRefreshInRedisWithTtl() throws Exception {
        User user = User.create("alice", "$2a$10$encodedHash", clock);
        setId(user, 7L);
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("password123", "$2a$10$encodedHash")).willReturn(true);
        given(jwtProvider.createAccess(7L, "alice")).willReturn("access-token");
        given(jwtProvider.createRefresh(7L)).willReturn("refresh-token");
        given(jwtProvider.getAccessTtlSec()).willReturn(900L);
        given(jwtProvider.getRefreshTtlSec()).willReturn(1209600L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        LoginResponse res = authService.login(new LoginRequest("alice", "password123"));

        assertThat(res.accessToken()).isEqualTo("access-token");
        assertThat(res.refreshToken()).isEqualTo("refresh-token");
        assertThat(res.expiresInSec()).isEqualTo(900L);

        verify(valueOperations).set(
                eq(RedisKeys.refresh(7L)),
                eq("refresh-token"),
                eq(Duration.ofSeconds(1209600L))
        );
    }

    private static void setId(User user, Long id) throws Exception {
        Field field = User.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(user, id);
    }
}
