package com.harness.ticket.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.reservation.domain.ReservationStatus;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ReservationConcurrencyIT extends IntegrationTestSupport {

    private static final Long CONCERT_ID = 1L;
    private static final Long SEAT_ID = 1L;

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

    /**
     * 100 스레드가 동일 좌석(concertId=1, seatId=1)에 동시 선점 시도 →
     * 정확히 1 성공 / 99 SEAT_ALREADY_HELD 실패 / DB row 1개 — Redis SET NX PX 단일 원자성 검증.
     */
    @Test
    void concurrentReserveSameSeat_exactlyOneSucceeds_andNinetyNineFail() throws Exception {
        int threads = 100;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        String body = "{\"concertId\":" + CONCERT_ID + ",\"seatId\":" + SEAT_ID + "}";

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/reservations")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 201) {
                        created.incrementAndGet();
                    } else if (status == 409) {
                        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
                        if ("SEAT_ALREADY_HELD".equals(root.path("code").asText())) {
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

        assertThat(created.get()).as("정확히 1 스레드만 좌석 선점 성공").isEqualTo(1);
        assertThat(conflict.get()).as("나머지 99 스레드는 SEAT_ALREADY_HELD 충돌").isEqualTo(99);
        assertThat(other.get()).as("기타 응답이 없어야 함").isEqualTo(0);

        long pendingRows = reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.PENDING)
                .count();
        assertThat(pendingRows).as("DB에는 정확히 1개의 PENDING 예약 row").isEqualTo(1);

        String seatVal = redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, SEAT_ID));
        assertThat(seatVal).as("Redis seat 키 값은 선점 사용자의 userId").isEqualTo(userId.toString());

        String countVal = redisTemplate.opsForValue().get(RedisKeys.count(userId, CONCERT_ID));
        assertThat(countVal).as("사용자 좌석 카운터는 1").isEqualTo("1");
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
