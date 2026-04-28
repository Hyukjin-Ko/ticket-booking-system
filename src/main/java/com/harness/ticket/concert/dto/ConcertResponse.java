package com.harness.ticket.concert.dto;

import com.harness.ticket.concert.domain.Concert;
import java.time.Instant;

public record ConcertResponse(
        Long id,
        String title,
        Instant startsAt,
        boolean queueEnabled
) {
    public static ConcertResponse from(Concert c) {
        return new ConcertResponse(c.getId(), c.getTitle(), c.getStartsAt(), c.isQueueEnabled());
    }
}
