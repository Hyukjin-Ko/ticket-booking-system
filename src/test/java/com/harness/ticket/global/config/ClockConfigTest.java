package com.harness.ticket.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void clockBeanIsSystemUtc() {
        Clock clock = new ClockConfig().clock();

        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock).isEqualTo(Clock.systemUTC());
    }
}
