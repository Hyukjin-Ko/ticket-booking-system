package com.harness.ticket.concert.dto;

import com.harness.ticket.concert.domain.Seat;

public record SeatResponse(Long id, String section, int rowNo, int colNo) {
    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getSection(), s.getRowNo(), s.getColNo());
    }
}
