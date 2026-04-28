package com.harness.ticket.reservation.service;

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
import com.harness.ticket.reservation.dto.ReservationRequest;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ReservationServiceQueueGateTest {

    private static final Instant NOW = Instant.parse("2026-04-29T12:00:00Z");

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
    private SetOperations<String, String> setOperations;

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
    void reserve_queueEnabled_userNotAdmitted_throwsNotAdmitted_andSkipsCountAndSetNxAndSave() throws Exception {
        Long userId = 7L;
        Long concertId = 2L;
        Long seatId = 100L;

        given(concertRepository.findById(concertId)).willReturn(Optional.of(concertWithId(concertId, true)));
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(RedisKeys.admit(concertId), userId.toString())).willReturn(false);

        assertThatThrownBy(() -> service.reserve(userId, new ReservationRequest(concertId, seatId)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_ADMITTED);

        verify(redisTemplate, never()).opsForValue();
        verify(seatRepository, never()).findByIdAndConcertId(any(), any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserve_queueEnabled_userAdmitted_proceedsThroughCountAndSetNxAndSave() throws Exception {
        Long userId = 7L;
        Long concertId = 2L;
        Long seatId = 100L;

        given(concertRepository.findById(concertId)).willReturn(Optional.of(concertWithId(concertId, true)));
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(RedisKeys.admit(concertId), userId.toString())).willReturn(true);
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
            f.set(r, 200L);
            return r;
        });

        service.reserve(userId, new ReservationRequest(concertId, seatId));

        verify(setOperations, times(1)).isMember(RedisKeys.admit(concertId), userId.toString());
        verify(valueOperations, times(1)).increment(RedisKeys.count(userId, concertId));
        verify(valueOperations, times(1)).setIfAbsent(
                eq(RedisKeys.seat(concertId, seatId)),
                eq(userId.toString()),
                eq(Duration.ofSeconds(600)));
        verify(reservationRepository, times(1)).save(any(Reservation.class));
    }

    @Test
    void reserve_queueDisabled_skipsAdmitCheck_andProceeds() throws Exception {
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
            f.set(r, 300L);
            return r;
        });

        service.reserve(userId, new ReservationRequest(concertId, seatId));

        verify(redisTemplate, never()).opsForSet();
        verify(setOperations, never()).isMember(any(), any());
        verify(reservationRepository, times(1)).save(any(Reservation.class));
    }
}
