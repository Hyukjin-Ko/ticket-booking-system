package com.harness.ticket.auth.service;

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
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Transactional
    public SignupResponse signup(SignupRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        String hash = passwordEncoder.encode(req.password());
        User saved = userRepository.save(User.create(req.username(), hash, clock));
        log.info("signup success userId={}", saved.getId());
        return new SignupResponse(saved.getId(), saved.getUsername());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        String access = jwtProvider.createAccess(user.getId(), user.getUsername());
        String refresh = jwtProvider.createRefresh(user.getId());

        redisTemplate.opsForValue().set(
                RedisKeys.refresh(user.getId()),
                refresh,
                Duration.ofSeconds(jwtProvider.getRefreshTtlSec())
        );

        log.info("login success userId={}", user.getId());
        return new LoginResponse(access, refresh, jwtProvider.getAccessTtlSec());
    }
}
