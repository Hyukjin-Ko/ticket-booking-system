package com.harness.ticket.reservation.dto;

import com.harness.ticket.reservation.domain.Reservation;
import com.harness.ticket.reservation.domain.ReservationStatus;
import java.time.Instant;

public record ReservationResponse(
        Long id,
        Long concertId,
        Long seatId,
        ReservationStatus status,
        Instant createdAt
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getConcertId(),
                r.getSeatId(),
                r.getStatus(),
                r.getCreatedAt()
        );
    }
}
