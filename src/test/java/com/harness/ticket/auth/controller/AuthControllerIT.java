package com.harness.ticket.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.domain.User;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@Import(AuthControllerIT.FixedClockConfig.class)
class AuthControllerIT extends IntegrationTestSupport {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanState() {
        userRepository.deleteAll();
        var keys = redisTemplate.keys("refresh:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void signup_success_returns201AndPersistsHashedUser() throws Exception {
        String body = "{\"username\":\"alice\",\"password\":\"password123\"}";

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다"))
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.username").value("alice"));

        User saved = userRepository.findByUsername("alice").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getPasswordHash())).isTrue();
    }

    @Test
    void signup_duplicateUsername_returns409UsernameTaken() throws Exception {
        userRepository.save(User.create("alice", passwordEncoder.encode("password123"), Clock.systemUTC()));

        String body = "{\"username\":\"alice\",\"password\":\"password123\"}";

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void signup_usernameWithSpecialChars_returns400WithFieldError() throws Exception {
        String body = "{\"username\":\"alice!@#\",\"password\":\"password123\"}";

        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        JsonNode errors = root.path("data").path("errors");
        assertThat(errors.isArray()).isTrue();
        boolean usernameErrorPresent = false;
        for (JsonNode node : errors) {
            if ("username".equals(node.path("field").asText())) {
                usernameErrorPresent = true;
                break;
            }
        }
        assertThat(usernameErrorPresent).isTrue();
    }

    @Test
    void signup_passwordTooShort_returns400WithFieldError() throws Exception {
        String body = "{\"username\":\"alice\",\"password\":\"abc1234\"}";

        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        JsonNode errors = root.path("data").path("errors");
        boolean passwordErrorPresent = false;
        for (JsonNode node : errors) {
            if ("password".equals(node.path("field").asText())) {
                passwordErrorPresent = true;
                break;
            }
        }
        assertThat(passwordErrorPresent).isTrue();
    }

    @Test
    void signup_koreanUsername_succeeds() throws Exception {
        String body = "{\"username\":\"홍길동1\",\"password\":\"password123\"}";

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("홍길동1"));

        assertThat(userRepository.existsByUsername("홍길동1")).isTrue();
    }

    @Test
    void login_success_returnsTokensAndStoresRefreshInRedis() throws Exception {
        userRepository.save(User.create("alice", passwordEncoder.encode("password123"), Clock.systemUTC()));
        Long userId = userRepository.findByUsername("alice").orElseThrow().getId();

        String body = "{\"username\":\"alice\",\"password\":\"password123\"}";

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresInSec").value(900))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String refreshToken = root.path("data").path("refreshToken").asText();

        String stored = redisTemplate.opsForValue().get(RedisKeys.refresh(userId));
        assertThat(stored).isEqualTo(refreshToken);
    }

    @Test
    void login_wrongPassword_returns401InvalidCredentials() throws Exception {
        userRepository.save(User.create("alice", passwordEncoder.encode("password123"), Clock.systemUTC()));

        String body = "{\"username\":\"alice\",\"password\":\"wrongpassword\"}";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_unknownUsername_returns401InvalidCredentialsSameMessage() throws Exception {
        String body = "{\"username\":\"ghost\",\"password\":\"password123\"}";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
