package com.harness.ticket.reservation.dto;

import jakarta.validation.constraints.NotNull;

public record ReservationRequest(
        @NotNull Long concertId,
        @NotNull Long seatId
) {
}
