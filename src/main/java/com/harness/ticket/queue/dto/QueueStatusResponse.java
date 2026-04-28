package com.harness.ticket.queue.dto;

public record QueueStatusResponse(
        int position,
        long etaSec,
        boolean admitted
) {
}
