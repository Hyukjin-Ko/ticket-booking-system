package com.harness.ticket.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RedisKeysTest {

    @Test
    void seatKeyFormat() {
        assertThat(RedisKeys.seat(1L, 2L)).isEqualTo("seat:1:2");
    }

    @Test
    void countKeyFormat() {
        assertThat(RedisKeys.count(42L, 7L)).isEqualTo("count:user:42:7");
    }

    @Test
    void queueKeyFormat() {
        assertThat(RedisKeys.queue(5L)).isEqualTo("queue:5");
    }

    @Test
    void admitKeyFormat() {
        assertThat(RedisKeys.admit(5L)).isEqualTo("admit:5");
    }

    @Test
    void refreshKeyFormat() {
        assertThat(RedisKeys.refresh(42L)).isEqualTo("refresh:42");
    }

    @Test
    void seatRejectsNullShowId() {
        assertThatThrownBy(() -> RedisKeys.seat(null, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void seatRejectsNullSeatId() {
        assertThatThrownBy(() -> RedisKeys.seat(1L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void countRejectsNullArguments() {
        assertThatThrownBy(() -> RedisKeys.count(null, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RedisKeys.count(1L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void queueRejectsNull() {
        assertThatThrownBy(() -> RedisKeys.queue(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void admitRejectsNull() {
        assertThatThrownBy(() -> RedisKeys.admit(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void refreshRejectsNull() {
        assertThatThrownBy(() -> RedisKeys.refresh(null))
                .isInstanceOf(NullPointerException.class);
    }
}
