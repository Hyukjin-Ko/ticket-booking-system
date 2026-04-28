package com.harness.ticket.queue.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.support.IntegrationTestSupport;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class QueueIT extends IntegrationTestSupport {

    private static final Long QUEUED_SHOW_ID = 2L;
    private static final Long NON_QUEUED_SHOW_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanState() {
        userRepository.deleteAll();
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
        flushRedis();
    }

    @Test
    void enter_queueDisabledConcert_returns400QueueNotEnabled() throws Exception {
        String token = signupAndLogin("alice", "password123");

        mockMvc.perform(post("/queue/" + NON_QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_NOT_ENABLED"));
    }

    @Test
    void enter_firstUser_returnsPositionZeroEtaOneAdmittedFalse() throws Exception {
        String token = signupAndLogin("alice", "password123");

        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.position").value(0))
                .andExpect(jsonPath("$.data.etaSec").value(1))
                .andExpect(jsonPath("$.data.admitted").value(false));
    }

    @Test
    void enter_sameUserTwice_keepsSamePositionAndScore() throws Exception {
        String token = signupAndLogin("alice", "password123");
        String userId = String.valueOf(userRepository.findByUsername("alice").orElseThrow().getId());

        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Double firstScore = redisTemplate.opsForZSet().score(RedisKeys.queue(QUEUED_SHOW_ID), userId);

        // 두 번째 호출은 NX이므로 score가 변하지 않아야 한다
        Thread.sleep(5);
        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(0));

        Double secondScore = redisTemplate.opsForZSet().score(RedisKeys.queue(QUEUED_SHOW_ID), userId);
        Long rank = redisTemplate.opsForZSet().rank(RedisKeys.queue(QUEUED_SHOW_ID), userId);
        assertThat(rank).isZero();
        assertThat(secondScore).isEqualTo(firstScore);
    }

    @Test
    void enter_secondUser_getsPositionOne() throws Exception {
        String aliceToken = signupAndLogin("alice", "password123");
        String bobToken = signupAndLogin("bobby", "password123");

        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(0));

        // score는 Instant ms 기반. 동일 ms에 두 enter가 발생하면 score가 같아져
        // ZRANK가 멤버 값(userId 문자열) 사전순으로 결정 → 사전순이 숫자 크기와 다를 수 있어 flaky.
        Thread.sleep(5);

        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(1))
                .andExpect(jsonPath("$.data.etaSec").value(1));
    }

    @Test
    void getMyStatus_inQueue_returnsCurrentRank() throws Exception {
        String token = signupAndLogin("alice", "password123");

        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/queue/" + QUEUED_SHOW_ID + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.position").value(0))
                .andExpect(jsonPath("$.data.etaSec").value(1))
                .andExpect(jsonPath("$.data.admitted").value(false));
    }

    @Test
    void getMyStatus_userNotInQueue_returns404NotInQueue() throws Exception {
        signupAndLogin("alice", "password123");
        String otherToken = signupAndLogin("bobby", "password123");

        mockMvc.perform(get("/queue/" + QUEUED_SHOW_ID + "/me")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_IN_QUEUE"));
    }

    @Test
    void enter_unknownShowId_returns404NotFound() throws Exception {
        String token = signupAndLogin("alice", "password123");

        mockMvc.perform(post("/queue/9999/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void enter_withoutToken_returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void getMyStatus_withoutToken_returns401Unauthorized() throws Exception {
        mockMvc.perform(get("/queue/" + QUEUED_SHOW_ID + "/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void enter_setsQueueKeyTtl_aroundShowStartsAtPlusOneHour() throws Exception {
        String token = signupAndLogin("alice", "password123");

        mockMvc.perform(post("/queue/" + QUEUED_SHOW_ID + "/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        Long ttl = redisTemplate.getExpire(RedisKeys.queue(QUEUED_SHOW_ID), TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive();
        // V6 seed: starts_at = NOW() + INTERVAL '30 days' (migration 시점 기준).
        // 컨테이너 reuse 가능성을 고려해 넉넉한 범위만 확인.
        long oneDaySec = 24L * 60 * 60;
        long thirtyDaysOneHourSec = 30L * 24 * 60 * 60 + 60L * 60;
        assertThat(ttl).isBetween(oneDaySec, thirtyDaysOneHourSec + 60);
    }

    private void flushRedis() {
        deleteByPattern("queue:*");
        deleteByPattern("admit:*");
        deleteByPattern("seat:*");
        deleteByPattern("count:user:*");
        deleteByPattern("refresh:*");
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String signupAndLogin(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        JsonNode root = objectMapper.readTree(login.getResponse().getContentAsByteArray());
        return root.path("data").path("accessToken").asText();
    }
}
