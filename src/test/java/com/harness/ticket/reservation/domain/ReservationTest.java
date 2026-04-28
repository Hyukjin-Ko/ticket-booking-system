package com.harness.ticket.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final Instant CREATED = Instant.parse("2026-04-28T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-04-28T10:05:00Z");

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void create_initialStatusPending() {
        Reservation r = Reservation.create(1L, 1L, 1L, fixed(CREATED));

        assertThat(r.getId()).isNull();
        assertThat(r.getUserId()).isEqualTo(1L);
        assertThat(r.getConcertId()).isEqualTo(1L);
        assertThat(r.getSeatId()).isEqualTo(1L);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(r.getCreatedAt()).isEqualTo(CREATED);
        assertThat(r.getPaidAt()).isNull();
        assertThat(r.getCancelledAt()).isNull();
    }

    @Test
    void pay_pendingToPaid_setsPaidAt() {
        Reservation r = Reservation.create(1L, 1L, 1L, fixed(CREATED));

        r.pay(fixed(LATER));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAID);
        assertThat(r.getPaidAt()).isEqualTo(LATER);
    }

    @Test
    void pay_alreadyPaid_isIdempotentAndDoesNotChangePaidAt() {
        Reservation r = Reservation.create(1L, 1L, 1L, fixed(CREATED));
        r.pay(fixed(LATER));
        Instant firstPaidAt = r.getPaidAt();

        Instant evenLater = Instant.parse("2026-04-28T10:10:00Z");
        r.pay(fixed(evenLater));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.PAID);
        assertThat(r.getPaidAt()).isEqualTo(firstPaidAt);
    }

    @Test
    void pay_whenCancelled_throwsIllegalStateException() {
        Reservation r = Reservation.create(1L, 1L, 1L, fixed(CREATED));
        r.cancel(fixed(LATER));

        assertThatThrownBy(() -> r.pay(fixed(LATER)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_pendingToCancelled_setsCancelledAt() {
        Reservation r = Reservation.create(1L, 1L, 1L, fixed(CREATED));

        r.cancel(fixed(LATER));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelledAt()).isEqualTo(LATER);
    }

    @Test
    void cancel_alreadyCancelled_isIdempotent() {
        Reservation r = Reservation.create(1L, 1L, 1L, fixed(CREATED));
        r.cancel(fixed(LATER));
        Instant firstCancelledAt = r.getCancelledAt();

        Instant evenLater = Instant.parse("2026-04-28T10:10:00Z");
        r.cancel(fixed(evenLater));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelledAt()).isEqualTo(firstCancelledAt);
    }

    @Test
    void cancel_whenPaid_throwsIllegalStateException() {
        Reservation r = Reservation.create(1L, 1L, 1L, fixed(CREATED));
        r.pay(fixed(LATER));

        assertThatThrownBy(() -> r.cancel(fixed(LATER)))
                .isInstanceOf(IllegalStateException.class);
    }
}
