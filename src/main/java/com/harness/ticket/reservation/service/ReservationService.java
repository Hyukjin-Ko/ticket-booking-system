package com.harness.ticket.reservation.service;

import com.harness.ticket.concert.domain.Concert;
import com.harness.ticket.concert.domain.Seat;
import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.concert.repository.SeatRepository;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.domain.ReservationStatus;
import com.harness.ticket.reservation.dto.ReservationRequest;
import com.harness.ticket.reservation.dto.ReservationResponse;
import com.harness.ticket.reservation.payment.PaymentGateway;
import com.harness.ticket.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final long SEAT_TTL_SEC = 600;
    private static final int SEAT_LIMIT = 4;

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final ConcertRepository concertRepository;
    private final StringRedisTemplate redisTemplate;
    private final PaymentGateway paymentGateway;
    private final Clock clock;

    @Transactional
    public ReservationResponse reserve(Long userId, ReservationRequest req) {
        Long concertId = req.concertId();
        Long seatId = req.seatId();

        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));

        if (concert.isQueueEnabled()) {
            // phase 4-queue에서 SISMEMBER admit:{showId} {userId} 검사 추가 예정
        }

        String countKey = RedisKeys.count(userId, concertId);
        String currentCount = redisTemplate.opsForValue().get(countKey);
        if (currentCount != null && Integer.parseInt(currentCount) >= SEAT_LIMIT) {
            throw new BusinessException(ErrorCode.SEAT_LIMIT_EXCEEDED);
        }

        Seat seat = seatRepository.findByIdAndConcertId(seatId, concertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        String seatKey = RedisKeys.seat(concertId, seat.getId());
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(seatKey, userId.toString(), Duration.ofSeconds(SEAT_TTL_SEC));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD);
        }

        Long newCount = redisTemplate.opsForValue().increment(countKey);
        if (newCount != null && newCount == 1L) {
            redisTemplate.expire(countKey, Duration.ofSeconds(SEAT_TTL_SEC));
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            redisTemplate.delete(seatKey);
                            redisTemplate.opsForValue().decrement(countKey);
                        } catch (Exception e) {
                            log.error("Compensation failed seatKey={} countKey={}", seatKey, countKey, e);
                        }
                    }
                }
            });
        }

        Reservation saved = reservationRepository.save(
                Reservation.create(userId, concertId, seat.getId(), clock));

        log.info("seat reserved userId={} concertId={} seatId={} reservationId={}",
                userId, concertId, seat.getId(), saved.getId());

        return ReservationResponse.from(saved);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ReservationResponse pay(Long userId, Long reservationId, String mockHeader) {
        Reservation r = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!r.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        if (r.getStatus() == ReservationStatus.PAID) {
            return ReservationResponse.from(r);
        }
        if (r.getStatus() == ReservationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATE);
        }

        String seatKey = RedisKeys.seat(r.getConcertId(), r.getSeatId());
        String stored = redisTemplate.opsForValue().get(seatKey);
        if (stored == null) {
            r.cancel(clock);
            throw new BusinessException(ErrorCode.EXPIRED_RESERVATION);
        }
        if (!stored.equals(userId.toString())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        boolean ok = paymentGateway.pay(reservationId, mockHeader);
        if (ok) {
            r.pay(clock);
        } else {
            r.cancel(clock);
        }
        redisTemplate.delete(seatKey);
        redisTemplate.opsForValue().decrement(RedisKeys.count(userId, r.getConcertId()));

        if (!ok) {
            log.info("payment failed reservationId={} userId={}", reservationId, userId);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }

        log.info("payment succeeded reservationId={} userId={}", reservationId, userId);
        return ReservationResponse.from(r);
    }
}
