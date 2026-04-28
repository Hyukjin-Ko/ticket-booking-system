package com.harness.ticket.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.concert.repository.SeatRepository;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.domain.ReservationStatus;
import com.harness.ticket.reservation.dto.ReservationResponse;
import com.harness.ticket.reservation.payment.PaymentGateway;
import com.harness.ticket.reservation.repository.ReservationRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ReservationCancelServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");
    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long CONCERT_ID = 1L;
    private static final Long SEAT_ID = 3L;
    private static final Long RESERVATION_ID = 100L;

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

    private static Reservation pendingReservation() throws Exception {
        Reservation r = Reservation.create(USER_ID, CONCERT_ID, SEAT_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        Field idField = Reservation.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(r, RESERVATION_ID);
        return r;
    }

    private static Reservation paidReservation() throws Exception {
        Reservation r = pendingReservation();
        r.pay(Clock.fixed(NOW, ZoneOffset.UTC));
        return r;
    }

    private static Reservation cancelledReservation() throws Exception {
        Reservation r = pendingReservation();
        r.cancel(Clock.fixed(NOW, ZoneOffset.UTC));
        return r;
    }

    @Test
    void cancelByUser_pending_callsEntityCancel_deletesSeatKey_decrementsCount() throws Exception {
        Reservation r = pendingReservation();
        String seatKey = RedisKeys.seat(CONCERT_ID, SEAT_ID);
        String countKey = RedisKeys.count(USER_ID, CONCERT_ID);

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(r));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        ReservationResponse res = service.cancelByUser(USER_ID, RESERVATION_ID);

        assertThat(res.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelledAt()).isEqualTo(NOW);
        verify(redisTemplate).delete(seatKey);
        verify(valueOperations).decrement(countKey);
    }

    @Test
    void cancelByUser_alreadyCancelled_isIdempotent_doesNotTouchRedis() throws Exception {
        Reservation r = cancelledReservation();
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(r));

        ReservationResponse res = service.cancelByUser(USER_ID, RESERVATION_ID);

        assertThat(res.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(redisTemplate, never()).delete(any(String.class));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void cancelByUser_paid_throwsInvalidReservationState() throws Exception {
        Reservation r = paidReservation();
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(r));

        assertThatThrownBy(() -> service.cancelByUser(USER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_RESERVATION_STATE);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAID);
        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void cancelByUser_otherUser_throwsForbidden() throws Exception {
        Reservation r = pendingReservation();
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(r));

        assertThatThrownBy(() -> service.cancelByUser(OTHER_USER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PENDING);
        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void cancelByUser_notFound_throwsReservationNotFound() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelByUser(USER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);

        verify(reservationRepository, times(1)).findById(RESERVATION_ID);
        verify(redisTemplate, never()).delete(any(String.class));
    }
}
