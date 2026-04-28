package com.harness.ticket.reservation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.harness.ticket.auth.domain.User;
import com.harness.ticket.auth.repository.UserRepository;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.domain.ReservationStatus;
import com.harness.ticket.reservation.repository.ReservationRepository;
import com.harness.ticket.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class PendingReservationCleanerIT extends IntegrationTestSupport {

    private static final Long CONCERT_ID = 1L;
    // BCrypt format: $2a$10$ (7) + 22 salt + 31 hash = 60 chars (matches users.password_hash length)
    private static final String FAKE_BCRYPT = "$2a$10$AAAAAAAAAAAAAAAAAAAAAuAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PendingReservationCleaner cleaner;

    private Long userId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();

        User u = User.create("alice", FAKE_BCRYPT, Clock.systemUTC());
        userRepository.save(u);
        userId = u.getId();
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        userRepository.deleteAll();
        flushRedis();
    }

    @Test
    void cleanup_stalePending_cancelsAndClearsRedis() {
        Long seatId = 1L;
        Instant stale = Instant.now().minus(Duration.ofMinutes(11));
        Reservation r = saveReservation(seatId, stale, ReservationStatus.PENDING);
        seedRedisHold(seatId);

        cleaner.cleanup();

        Reservation reloaded = reservationRepository.findById(r.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reloaded.getCancelledAt()).isNotNull();

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, seatId))).isNull();
        String count = redisTemplate.opsForValue().get(RedisKeys.count(userId, CONCERT_ID));
        assertThat(count == null || Integer.parseInt(count) <= 0).isTrue();
    }

    @Test
    void cleanup_freshPending_isNotTouched() {
        Long seatId = 2L;
        Reservation r = saveReservation(seatId, Instant.now(), ReservationStatus.PENDING);
        seedRedisHold(seatId);

        cleaner.cleanup();

        Reservation reloaded = reservationRepository.findById(r.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(reloaded.getCancelledAt()).isNull();

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, seatId))).isNotNull();
    }

    @Test
    void cleanup_mixedStaleAndFresh_onlyStaleIsCancelled() {
        Long staleSeat = 3L;
        Long freshSeat = 4L;
        Reservation staleR = saveReservation(staleSeat,
                Instant.now().minus(Duration.ofMinutes(15)), ReservationStatus.PENDING);
        Reservation freshR = saveReservation(freshSeat,
                Instant.now(), ReservationStatus.PENDING);
        seedRedisHold(staleSeat);
        seedRedisHold(freshSeat);

        cleaner.cleanup();

        assertThat(reservationRepository.findById(staleR.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservationRepository.findById(freshR.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING);

        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, staleSeat))).isNull();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.seat(CONCERT_ID, freshSeat))).isNotNull();
    }

    @Test
    void cleanup_paidAndCancelledRows_areNotTouched_evenIfOld() {
        Long stalePendingSeat = 5L;
        Long stalePaidSeat = 6L;
        Long staleCancelledSeat = 7L;

        Reservation stalePending = saveReservation(stalePendingSeat,
                Instant.now().minus(Duration.ofMinutes(20)), ReservationStatus.PENDING);
        Reservation stalePaid = saveReservation(stalePaidSeat,
                Instant.now().minus(Duration.ofMinutes(20)), ReservationStatus.PAID);
        Reservation staleCancelled = saveReservation(staleCancelledSeat,
                Instant.now().minus(Duration.ofMinutes(20)), ReservationStatus.CANCELLED);

        Instant paidAtBefore = stalePaid.getPaidAt();
        Instant cancelledAtBefore = staleCancelled.getCancelledAt();

        cleaner.cleanup();

        assertThat(reservationRepository.findById(stalePending.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);

        Reservation paidReloaded = reservationRepository.findById(stalePaid.getId()).orElseThrow();
        assertThat(paidReloaded.getStatus()).isEqualTo(ReservationStatus.PAID);
        assertThat(paidReloaded.getPaidAt()).isEqualTo(paidAtBefore);
        assertThat(paidReloaded.getCancelledAt()).isNull();

        Reservation cancelledReloaded = reservationRepository.findById(staleCancelled.getId()).orElseThrow();
        assertThat(cancelledReloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelledReloaded.getCancelledAt()).isEqualTo(cancelledAtBefore);
    }

    private Reservation saveReservation(Long seatId, Instant createdAt, ReservationStatus targetStatus) {
        Clock fixed = Clock.fixed(createdAt, ZoneOffset.UTC);
        Reservation r = Reservation.create(userId, CONCERT_ID, seatId, fixed);
        if (targetStatus == ReservationStatus.PAID) {
            r.pay(fixed);
        } else if (targetStatus == ReservationStatus.CANCELLED) {
            r.cancel(fixed);
        }
        return reservationRepository.save(r);
    }

    private void seedRedisHold(Long seatId) {
        redisTemplate.opsForValue().set(
                RedisKeys.seat(CONCERT_ID, seatId), userId.toString(), Duration.ofMinutes(10));
        redisTemplate.opsForValue().increment(RedisKeys.count(userId, CONCERT_ID));
        redisTemplate.expire(RedisKeys.count(userId, CONCERT_ID), Duration.ofMinutes(10));
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
}
