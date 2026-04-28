package com.harness.ticket.concert.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeatTest {

    @Test
    void create_setsFieldsWithoutId() {
        Seat seat = Seat.create(1L, "A", 3, 7);

        assertThat(seat.getId()).isNull();
        assertThat(seat.getConcertId()).isEqualTo(1L);
        assertThat(seat.getSection()).isEqualTo("A");
        assertThat(seat.getRowNo()).isEqualTo(3);
        assertThat(seat.getColNo()).isEqualTo(7);
    }
}
