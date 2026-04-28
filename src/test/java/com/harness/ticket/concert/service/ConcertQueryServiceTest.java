package com.harness.ticket.concert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.harness.ticket.concert.domain.Concert;
import com.harness.ticket.concert.domain.Seat;
import com.harness.ticket.concert.dto.ConcertResponse;
import com.harness.ticket.concert.dto.SeatResponse;
import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.concert.repository.SeatRepository;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.global.response.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ConcertQueryServiceTest {

    @Mock
    private ConcertRepository concertRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ConcertQueryService concertQueryService;

    @Test
    void findAll_returnsPagedConcertResponses() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC);
        Concert c = Concert.create("하네스 페스티벌 2026", Instant.parse("2026-05-28T20:00:00Z"), false, clock);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Concert> page = new PageImpl<>(List.of(c), pageable, 1);
        given(concertRepository.findAll(any(Pageable.class))).willReturn(page);

        PageResponse<ConcertResponse> response = concertQueryService.findAll(pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).title()).isEqualTo("하네스 페스티벌 2026");
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
    }

    @Test
    void findSeats_unknownConcertId_throwsNotFound() {
        given(concertRepository.existsById(999L)).willReturn(false);

        assertThatThrownBy(() -> concertQueryService.findSeats(999L, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void findSeats_existingConcert_returnsSeatResponsesPage() {
        given(concertRepository.existsById(1L)).willReturn(true);
        Pageable pageable = PageRequest.of(0, 20);
        Seat s1 = Seat.create(1L, "A", 1, 1);
        Seat s2 = Seat.create(1L, "A", 1, 2);
        Page<Seat> page = new PageImpl<>(List.of(s1, s2), pageable, 100);
        given(seatRepository.findByConcertId(eq(1L), any(Pageable.class))).willReturn(page);

        PageResponse<SeatResponse> response = concertQueryService.findSeats(1L, pageable);

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).section()).isEqualTo("A");
        assertThat(response.content().get(1).colNo()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(100);
        assertThat(response.totalPages()).isEqualTo(5);
    }
}
