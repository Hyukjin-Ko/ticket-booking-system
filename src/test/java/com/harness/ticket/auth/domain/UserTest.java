package com.harness.ticket.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void create_setsFieldsAndCreatedAtFromClock() {
        Instant fixedInstant = Instant.parse("2026-04-28T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        User user = User.create("alice", "$2a$10$hash", fixedClock);

        assertThat(user.getId()).isNull();
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$hash");
        assertThat(user.getCreatedAt()).isEqualTo(fixedInstant);
    }
}
