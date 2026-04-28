package com.harness.ticket.concert.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ConcertTest {

    @Test
    void create_setsFieldsAndCreatedAtFromClock() {
        Instant now = Instant.parse("2026-04-28T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Instant startsAt = Instant.parse("2026-05-28T20:00:00Z");

        Concert c = Concert.create("하네스 페스티벌 2026", startsAt, false, clock);

        assertThat(c.getId()).isNull();
        assertThat(c.getTitle()).isEqualTo("하네스 페스티벌 2026");
        assertThat(c.getStartsAt()).isEqualTo(startsAt);
        assertThat(c.isQueueEnabled()).isFalse();
        assertThat(c.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void create_withQueueEnabledTrue_storesFlag() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC);

        Concert c = Concert.create("VIP", Instant.parse("2026-06-01T19:00:00Z"), true, clock);

        assertThat(c.isQueueEnabled()).isTrue();
    }
}
