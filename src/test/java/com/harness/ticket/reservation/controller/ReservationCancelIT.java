package com.harness.ticket.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.domain.ReservationStatus;
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
class ReservationCancelIT extends IntegrationTestSupport {

    private static final Long CONCERT_ID = 1L;
    private static final Long SEAT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReservationRepository reservationRepository;

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
    void cancel_pending_returns200_andClearsRedisAndSetsCancelledAt() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, SEAT_ID))).isNotNull();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.count(userId, CONCERT_ID))).isEqualTo("1");

        mockMvc.perform(delete("/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("예약이 취소되었습니다"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelledAt()).isNotNull();

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, SEAT_ID))).isNull();
        String count = redisTemplate.opsForValue().get(RedisKeys.count(userId, CONCERT_ID));
        assertThat(count == null || Integer.parseInt(count) <= 0).isTrue();
    }

    @Test
    void cancel_idempotent_secondCallReturns200_andDoesNotResurrectRedis() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        mockMvc.perform(delete("/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 두 번째 호출도 200 + 동일 상태
        mockMvc.perform(delete("/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // Redis 키는 첫 번째 호출에서 정리됐고 두 번째 호출이 다시 만들지 않아야 함
        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, SEAT_ID))).isNull();
    }

    @Test
    void cancel_paid_returns409InvalidReservationState() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAID);
    }

    @Test
    void cancel_otherUser_returns403Forbidden() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        String bobToken = signupAndLogin("bobby", "password123");

        mockMvc.perform(delete("/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    void cancel_notFound_returns404ReservationNotFound() throws Exception {
        mockMvc.perform(delete("/reservations/999999")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void cancel_noToken_returns401Unauthorized() throws Exception {
        mockMvc.perform(delete("/reservations/1"))
                .andExpect(status().isUnauthorized());
    }

    private Long reserveSeat(Long seatId) throws Exception {
        MvcResult result = mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concertId\":" + CONCERT_ID + ",\"seatId\":" + seatId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return root.path("data").path("id").asLong();
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
