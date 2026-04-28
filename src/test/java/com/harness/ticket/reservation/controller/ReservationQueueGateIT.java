package com.harness.ticket.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.concert.repository.SeatRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.queue.scheduler.AdmitWorker;
import com.harness.ticket.reservation.domain.ReservationStatus;
import com.harness.ticket.reservation.repository.ReservationRepository;
import com.harness.ticket.support.IntegrationTestSupport;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// tick-interval-sec를 3600초로 두어 @Scheduled가 테스트 도중 끼어들지 않게 한다.
// admit Set 채우기는 worker.runOnce() 직접 호출로만 수행한다 — 시간 의존 제거.
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "queue.tick-interval-sec=3600",
        "queue.admits-per-tick=100"
})
class ReservationQueueGateIT extends IntegrationTestSupport {

    private static final Long QUEUED_CONCERT_ID = 2L;
    private static final Long NON_QUEUED_CONCERT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AdmitWorker worker;

    private String accessToken;
    private Long userId;
    private Long queuedSeatId;
    private Long queuedSeatIdAlt;
    private Long nonQueuedSeatId;

    @BeforeEach
    void setUp() throws Exception {
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();
        accessToken = signupAndLogin("alice", "password123");
        userId = userRepository.findByUsername("alice").orElseThrow().getId();
        queuedSeatId = firstSeatId(QUEUED_CONCERT_ID, 0);
        queuedSeatIdAlt = firstSeatId(QUEUED_CONCERT_ID, 1);
        nonQueuedSeatId = firstSeatId(NON_QUEUED_CONCERT_ID, 0);
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();
    }

    @Test
    void reserve_queueEnabled_withoutAdmit_returns403_andDoesNotTouchRedisOrDb() throws Exception {
        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(QUEUED_CONCERT_ID, queuedSeatId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));

        assertThat(reservationRepository.findAll()).isEmpty();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(QUEUED_CONCERT_ID, queuedSeatId))).isNull();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.count(userId, QUEUED_CONCERT_ID))).isNull();
    }

    @Test
    void reserve_queueEnabled_enterThenWorkerThenReserve_returns201Pending() throws Exception {
        mockMvc.perform(post("/queue/" + QUEUED_CONCERT_ID + "/enter")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(0));

        worker.runOnce();

        assertThat(redisTemplate.opsForSet()
                .isMember(RedisKeys.admit(QUEUED_CONCERT_ID), userId.toString())).isTrue();

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(QUEUED_CONCERT_ID, queuedSeatId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(QUEUED_CONCERT_ID, queuedSeatId)))
                .isEqualTo(userId.toString());
    }

    @Test
    void reserve_queueEnabled_enteredButNotAdmittedYet_returns403() throws Exception {
        mockMvc.perform(post("/queue/" + QUEUED_CONCERT_ID + "/enter")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 워커 호출 안 함 → admit Set에 없음

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(QUEUED_CONCERT_ID, queuedSeatId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));

        assertThat(reservationRepository.findAll()).isEmpty();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(QUEUED_CONCERT_ID, queuedSeatId))).isNull();
    }

    @Test
    void reserve_queueEnabled_userAPays_userBAdmittedReservesDifferentSeat_returns201() throws Exception {
        // A: enter → admit → reserve → pay
        mockMvc.perform(post("/queue/" + QUEUED_CONCERT_ID + "/enter")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        worker.runOnce();

        MvcResult aReserve = mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(QUEUED_CONCERT_ID, queuedSeatId)))
                .andExpect(status().isCreated())
                .andReturn();
        Long aReservationId = objectMapper.readTree(aReserve.getResponse().getContentAsByteArray())
                .path("data").path("id").asLong();

        mockMvc.perform(post("/reservations/" + aReservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // B: signup → enter → admit → 다른 좌석 reserve
        String bobToken = signupAndLogin("bobby", "password123");

        mockMvc.perform(post("/queue/" + QUEUED_CONCERT_ID + "/enter")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
        worker.runOnce();

        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(QUEUED_CONCERT_ID, queuedSeatIdAlt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void reserve_queueDisabledConcert_skipsAdmitCheck_returns201() throws Exception {
        // queueEnabled=false 공연(id=1)은 admit 검사 우회 — phase 3 회귀 검증
        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(NON_QUEUED_CONCERT_ID, nonQueuedSeatId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(reservationRepository.findAll())
                .anyMatch(r -> r.getStatus() == ReservationStatus.PENDING);
    }

    @Test
    void reserve_unknownConcertId_returns404NotFound() throws Exception {
        mockMvc.perform(post("/reservations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(999999L, 1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void reserve_withoutToken_returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(QUEUED_CONCERT_ID, queuedSeatId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String reserveBody(Long concertId, Long seatId) {
        return "{\"concertId\":" + concertId + ",\"seatId\":" + seatId + "}";
    }

    private Long firstSeatId(Long concertId, int index) {
        return seatRepository.findByConcertId(concertId, PageRequest.of(index, 1))
                .getContent().get(0).getId();
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
