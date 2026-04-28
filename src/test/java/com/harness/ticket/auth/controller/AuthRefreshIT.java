package com.harness.ticket.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.global.security.JwtProperties;
import com.harness.ticket.global.security.JwtProvider;
import com.harness.ticket.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@Import(AuthRefreshIT.FixedClockConfig.class)
class AuthRefreshIT extends IntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");

    static class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration d) {
            this.current = this.current.plus(d);
        }

        void reset(Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.harness.ticket.auth.repository.UserRepository userRepository;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanState() {
        clock.reset(NOW);
        userRepository.deleteAll();
        var keys = redisTemplate.keys("refresh:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void refresh_rotation_issuesNewTokensAndOldOneTriggersReuseDetection() throws Exception {
        Tokens initial = signupAndLogin("alice", "password123");
        Long userId = userRepository.findByUsername("alice").orElseThrow().getId();

        clock.advance(Duration.ofSeconds(1));

        String body = "{\"refreshToken\":\"" + initial.refreshToken + "\"}";

        MvcResult result = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("토큰이 갱신되었습니다"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresInSec").value(900))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String newAccess = root.path("data").path("accessToken").asText();
        String newRefresh = root.path("data").path("refreshToken").asText();
        assertThat(newAccess).isNotEqualTo(initial.accessToken);
        assertThat(newRefresh).isNotEqualTo(initial.refreshToken);

        String stored = redisTemplate.opsForValue().get(RedisKeys.refresh(userId));
        assertThat(stored).isEqualTo(newRefresh);

        String reuseBody = "{\"refreshToken\":\"" + initial.refreshToken + "\"}";
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reuseBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REUSE_DETECTED"));

        assertThat(redisTemplate.opsForValue().get(RedisKeys.refresh(userId))).isNull();
    }

    @Test
    void refresh_expiredToken_returns401TokenExpired() throws Exception {
        signupAndLogin("alice", "password123");
        Long userId = userRepository.findByUsername("alice").orElseThrow().getId();

        Clock pastClock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC);
        JwtProvider pastProvider = new JwtProvider(jwtProperties, pastClock);
        String expiredRefresh = pastProvider.createRefresh(userId);

        String body = "{\"refreshToken\":\"" + expiredRefresh + "\"}";

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void refresh_tamperedToken_returns401InvalidToken() throws Exception {
        Tokens initial = signupAndLogin("alice", "password123");

        String tampered = tamperSignature(initial.refreshToken);

        String body = "{\"refreshToken\":\"" + tampered + "\"}";

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void refresh_accessTokenUsedAsRefresh_returns401InvalidToken() throws Exception {
        Tokens initial = signupAndLogin("alice", "password123");

        String body = "{\"refreshToken\":\"" + initial.accessToken + "\"}";

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void logout_withValidAccessToken_clearsRefreshAndBlocksFurtherRefresh() throws Exception {
        Tokens initial = signupAndLogin("alice", "password123");
        Long userId = userRepository.findByUsername("alice").orElseThrow().getId();

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + initial.accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("로그아웃이 되었습니다"));

        assertThat(redisTemplate.opsForValue().get(RedisKeys.refresh(userId))).isNull();

        String body = "{\"refreshToken\":\"" + initial.refreshToken + "\"}";
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REUSE_DETECTED"));
    }

    @Test
    void logout_withoutBearerToken_returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void logout_withTamperedAccessToken_returns401Unauthorized() throws Exception {
        Tokens initial = signupAndLogin("alice", "password123");

        String tampered = tamperSignature(initial.accessToken);

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private Tokens signupAndLogin(String username, String password) throws Exception {
        String signupBody = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isCreated());

        String loginBody = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(login.getResponse().getContentAsByteArray());
        return new Tokens(
                root.path("data").path("accessToken").asText(),
                root.path("data").path("refreshToken").asText()
        );
    }

    private static String tamperSignature(String token) {
        int dot = token.lastIndexOf('.');
        String body = token.substring(0, dot + 1);
        String sig = token.substring(dot + 1);
        char first = sig.charAt(0);
        char replacement = (first == 'A') ? 'B' : 'A';
        return body + replacement + sig.substring(1);
    }

    private record Tokens(String accessToken, String refreshToken) {}
}
