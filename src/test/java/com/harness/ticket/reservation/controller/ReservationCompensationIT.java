package com.harness.ticket.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.repository.ReservationRepository;
import com.harness.ticket.support.IntegrationTestSupport;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ReservationCompensationIT extends IntegrationTestSupport {

    private static final Long CONCERT_ID = 1L;
    private static final Long SEAT_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @SpyBean
    private ReservationRepository reservationRepository;

    private String accessToken;
    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();
        accessToken = signupAndLogin("alice", "password123");
        userId = userRepository.findByUsername("alice").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();
    }

    /**
     * Redis SET NX 성공 후 DB save 강제 실패 → afterCompletion 보상이
     * Redis seat 키와 카운터를 정리해야 한다 (TTL 안전망 검증 아님, 즉시 삭제 검증).
     */
    @Test
    void dbInsertFailure_triggersRedisCompensation() throws Exception {
        doThrow(new RuntimeException("simulated DB failure"))
                .when(reservationRepository).save(any(Reservation.class));

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concertId\":" + CONCERT_ID + ",\"seatId\":" + SEAT_ID + "}"))
                .andReturn();

        String seatVal = redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, SEAT_ID));
        assertThat(seatVal).as("보상으로 seat 키가 삭제되어야 함").isNull();

        String countVal = redisTemplate.opsForValue().get(RedisKeys.count(userId, CONCERT_ID));
        // 보상으로 DECR — 1 → 0 가 되거나 키 자체가 없을 수 있음(TTL 또는 음수 감소)
        if (countVal != null) {
            assertThat(Integer.parseInt(countVal)).as("카운터가 보상으로 감소되어야 함").isLessThanOrEqualTo(0);
        }
    }

    private void flushRedis() {
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
