package com.harness.ticket.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ConcertRepository concertRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private PaymentGateway paymentGateway;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationService(
                reservationRepository, seatRepository, concertRepository, redisTemplate, paymentGateway, clock);
    }

    private static Concert concertWithId(Long id, boolean queueEnabled) throws Exception {
        Concert c = Concert.create("title", NOW, queueEnabled, Clock.fixed(NOW, ZoneOffset.UTC));
        Field f = Concert.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(c, id);
        return c;
    }

    private static Seat seatWithId(Long seatId, Long concertId) throws Exception {
        Seat s = Seat.create(concertId, "A", 1, 1);
        Field f = Seat.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(s, seatId);
        return s;
    }

    @Test
    void reserve_success_setsSeatKey_increments_savesReservation() throws Exception {
        Long userId = 7L;
        Long concertId = 1L;
        Long seatId = 1L;

        given(concertRepository.findById(concertId)).willReturn(Optional.of(concertWithId(concertId, false)));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.count(userId, concertId))).willReturn(null);
        given(seatRepository.findByIdAndConcertId(seatId, concertId))
                .willReturn(Optional.of(seatWithId(seatId, concertId)));
        given(valueOperations.setIfAbsent(
                eq(RedisKeys.seat(concertId, seatId)),
                eq(userId.toString()),
                eq(Duration.ofSeconds(600)))).willReturn(true);
        given(valueOperations.increment(RedisKeys.count(userId, concertId))).willReturn(1L);
        given(reservationRepository.save(any(Reservation.class))).willAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            Field f = Reservation.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(r, 100L);
            return r;
        });

        ReservationResponse res = service.reserve(userId, new ReservationRequest(concertId, seatId));

        assertThat(res.id()).isEqualTo(100L);
        assertThat(res.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(res.concertId()).isEqualTo(concertId);
        assertThat(res.seatId()).isEqualTo(seatId);
        verify(redisTemplate).expire(RedisKeys.count(userId, concertId), Duration.ofSeconds(600));
        verify(reservationRepository, times(1)).save(any(Reservation.class));
    }

    @Test
    void reserve_concertNotFound_throwsNotFound() {
        given(concertRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(7L, new ReservationRequest(99L, 1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserve_countAtLimit_throwsSeatLimitExceeded_andDoesNotCallSetIfAbsent() throws Exception {
        Long userId = 7L;
        Long concertId = 1L;

        given(concertRepository.findById(concertId)).willReturn(Optional.of(concertWithId(concertId, false)));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.count(userId, concertId))).willReturn("4");

        assertThatThrownBy(() -> service.reserve(userId, new ReservationRequest(concertId, 1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SEAT_LIMIT_EXCEEDED);

        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserve_seatNotFound_throwsSeatNotFound_andDoesNotCallSetIfAbsent() throws Exception {
        Long userId = 7L;
        Long concertId = 1L;
        Long seatId = 999L;

        given(concertRepository.findById(concertId)).willReturn(Optional.of(concertWithId(concertId, false)));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.count(userId, concertId))).willReturn(null);
        given(seatRepository.findByIdAndConcertId(seatId, concertId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(userId, new ReservationRequest(concertId, seatId)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SEAT_NOT_FOUND);

        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserve_setIfAbsentFails_throwsSeatAlreadyHeld() throws Exception {
        Long userId = 7L;
        Long concertId = 1L;
        Long seatId = 1L;

        given(concertRepository.findById(concertId)).willReturn(Optional.of(concertWithId(concertId, false)));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(RedisKeys.count(userId, concertId))).willReturn(null);
        given(seatRepository.findByIdAndConcertId(seatId, concertId))
                .willReturn(Optional.of(seatWithId(seatId, concertId)));
        given(valueOperations.setIfAbsent(
                eq(RedisKeys.seat(concertId, seatId)),
                eq(userId.toString()),
                eq(Duration.ofSeconds(600)))).willReturn(false);

        assertThatThrownBy(() -> service.reserve(userId, new ReservationRequest(concertId, seatId)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SEAT_ALREADY_HELD);

        verify(valueOperations, never()).increment(any(String.class));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserve_compensationOnRollback_deletesSeatKeyAndDecrementsCount() throws Exception {
        Long userId = 7L;
        Long concertId = 1L;
        Long seatId = 1L;
        String seatKey = RedisKeys.seat(concertId, seatId);
        String countKey = RedisKeys.count(userId, concertId);

        given(concertRepository.findById(concertId)).willReturn(Optional.of(concertWithId(concertId, false)));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(countKey)).willReturn(null);
        given(seatRepository.findByIdAndConcertId(seatId, concertId))
                .willReturn(Optional.of(seatWithId(seatId, concertId)));
        given(valueOperations.setIfAbsent(eq(seatKey), eq(userId.toString()), eq(Duration.ofSeconds(600))))
                .willReturn(true);
        given(valueOperations.increment(countKey)).willReturn(1L);
        given(reservationRepository.save(any(Reservation.class)))
                .willThrow(new RuntimeException("DB insert failed"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.reserve(userId, new ReservationRequest(concertId, seatId)))
                    .isInstanceOf(RuntimeException.class);

            // Manually trigger afterCompletion as ROLLED_BACK
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(redisTemplate).delete(seatKey);
        verify(valueOperations).decrement(countKey);
    }
}
