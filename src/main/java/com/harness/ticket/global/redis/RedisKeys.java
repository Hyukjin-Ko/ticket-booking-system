package com.harness.ticket.global.redis;

import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RedisKeys {

    public static String seat(Long showId, Long seatId) {
        Objects.requireNonNull(showId, "showId must not be null");
        Objects.requireNonNull(seatId, "seatId must not be null");
        return "seat:" + showId + ":" + seatId;
    }

    public static String count(Long userId, Long showId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(showId, "showId must not be null");
        return "count:user:" + userId + ":" + showId;
    }

    public static String queue(Long showId) {
        Objects.requireNonNull(showId, "showId must not be null");
        return "queue:" + showId;
    }

    public static String admit(Long showId) {
        Objects.requireNonNull(showId, "showId must not be null");
        return "admit:" + showId;
    }

    public static String refresh(Long userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return "refresh:" + userId;
    }
}
