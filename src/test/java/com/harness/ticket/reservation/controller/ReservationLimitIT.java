package com.harness.ticket.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.reservation.repository.ReservationRepository;
import com.harness.ticket.support.IntegrationTestSupport;
import java.util.Set;
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
class ReservationLimitIT extends IntegrationTestSupport {

    private static final Long CONCERT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

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

    @Test
    void reserveFourSeats_thenFifth_returns409SeatLimitExceeded() throws Exception {
        for (long seatId = 1L; seatId <= 4L; seatId++) {
            mockMvc.perform(post("/reservations")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"concertId\":" + CONCERT_ID + ",\"seatId\":" + seatId + "}"))
                    .andExpect(status().isCreated());
        }

        String countVal = redisTemplate.opsForValue().get(RedisKeys.count(userId, CONCERT_ID));
        assertThat(countVal).isEqualTo("4");

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concertId\":" + CONCERT_ID + ",\"seatId\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SEAT_LIMIT_EXCEEDED"));

        // 5번째 시도는 SET NX 호출 전이므로 seat:1:5 키는 생성되지 않아야 한다
        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, 5L))).isNull();
    }

    @Test
    void reserveSeatNotInConcert_returns404SeatNotFound() throws Exception {
        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concertId\":" + CONCERT_ID + ",\"seatId\":99999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
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
