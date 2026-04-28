package com.harness.ticket.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.domain.ReservationStatus;
import com.harness.ticket.reservation.payment.PaymentGateway;
import com.harness.ticket.reservation.repository.ReservationRepository;
import com.harness.ticket.support.IntegrationTestSupport;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
class ReservationPaymentIT extends IntegrationTestSupport {

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

    @SpyBean
    private PaymentGateway paymentGateway;

    private String accessToken;
    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        reset(paymentGateway);
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();
        accessToken = signupAndLogin("alice", "password123");
        userId = userRepository.findByUsername("alice").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        reset(paymentGateway);
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();
    }

    @Test
    void pay_deterministicSuccess_returns200_andUpdatesDbAndRedis() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("결제가 완료되었습니다"))
                .andExpect(jsonPath("$.data.status").value("PAID"));

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAID);
        assertThat(r.getPaidAt()).isNotNull();

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, SEAT_ID))).isNull();
        String count = redisTemplate.opsForValue().get(RedisKeys.count(userId, CONCERT_ID));
        assertThat(count == null || Integer.parseInt(count) <= 0).isTrue();
    }

    @Test
    void pay_deterministicFail_returns402_andCancelsReservation() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "fail"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"));

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelledAt()).isNotNull();

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, SEAT_ID))).isNull();
    }

    @Test
    void pay_idempotent_secondCallReturnsPaid_andDoesNotCallGatewayAgain() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // 두 번째 호출도 200 + 동일 응답
        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // gateway는 1번만 호출되어야 함
        verify(paymentGateway, times(1)).pay(eq(reservationId), eq("success"));

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAID);
    }

    @Test
    void pay_expiredSeatKey_returns410_andCancelsReservation() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        // Redis 좌석 키를 수동 삭제하여 만료 상황 시뮬레이션
        redisTemplate.delete(RedisKeys.seat(CONCERT_ID, SEAT_ID));

        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("EXPIRED_RESERVATION"));

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void pay_otherUser_returns403() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        String bobToken = signupAndLogin("bobby", "password123");

        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + bobToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void pay_reservationNotFound_returns404() throws Exception {
        mockMvc.perform(post("/reservations/999999/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void pay_cancelledReservation_returns409InvalidState() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        // 결제 실패로 CANCELLED 상태로 만든 뒤
        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "fail"))
                .andExpect(status().isPaymentRequired());

        // 같은 reservation에 다시 결제 시도 → INVALID_RESERVATION_STATE
        mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Mock-Pay-Result", "success"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));
    }

    /**
     * 같은 reservation에 동시 pay 호출 → 1 성공(200, PAID) / 1 충돌(409, RESERVATION_CONFLICT).
     * paymentGateway.pay에서 barrier로 두 스레드가 동시에 지나가도록 강제 → 두 트랜잭션이
     * 동일 version=0을 읽고 commit 시점에 OptimisticLockException 발생.
     */
    @Test
    void pay_concurrent_optimisticLockConflict_oneSuccessOneConflict() throws Exception {
        Long reservationId = reserveSeat(SEAT_ID);

        int threads = 2;
        CountDownLatch barrier = new CountDownLatch(threads);
        doAnswer(inv -> {
            barrier.countDown();
            // 두 스레드가 모두 도달할 때까지 대기 → 동시 commit 유도
            barrier.await(5, TimeUnit.SECONDS);
            return true;
        }).when(paymentGateway).pay(eq(reservationId), any());

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/reservations/" + reservationId + "/pay")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .header("X-Mock-Pay-Result", "success"))
                            .andReturn();
                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 200) {
                        ok.incrementAndGet();
                    } else if (statusCode == 409) {
                        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
                        if ("RESERVATION_CONFLICT".equals(root.path("code").asText())) {
                            conflict.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();

        assertThat(ok.get()).as("정확히 1 스레드만 결제 성공").isEqualTo(1);
        assertThat(conflict.get()).as("나머지 1 스레드는 RESERVATION_CONFLICT 충돌").isEqualTo(1);
        assertThat(other.get()).as("기타 응답이 없어야 함").isEqualTo(0);

        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAID);
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
