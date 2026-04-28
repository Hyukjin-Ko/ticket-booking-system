package com.harness.ticket.reservation.scheduler;

import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingReservationCleaner {

    private static final Duration PENDING_THRESHOLD = Duration.ofMinutes(10);

    private final ReservationRepository reservationRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void cleanup() {
        try {
            Instant cutoff = Instant.now(clock).minus(PENDING_THRESHOLD);
            List<Reservation> stale = reservationRepository.findStaleForUpdate(cutoff);
            if (stale.isEmpty()) {
                return;
            }

            for (Reservation r : stale) {
                r.cancel(clock);
                try {
                    redisTemplate.delete(RedisKeys.seat(r.getConcertId(), r.getSeatId()));
                    redisTemplate.opsForValue().decrement(RedisKeys.count(r.getUserId(), r.getConcertId()));
                } catch (Exception e) {
                    log.error("PendingReservationCleaner redis cleanup failed reservationId={}",
                            r.getId(), e);
                }
            }
            log.info("PendingReservationCleaner cancelled count={}", stale.size());
        } catch (Exception e) {
            log.error("PendingReservationCleaner failed", e);
        }
    }
}
