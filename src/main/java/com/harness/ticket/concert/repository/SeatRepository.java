package com.harness.ticket.concert.repository;

import com.harness.ticket.concert.domain.Seat;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    Page<Seat> findByConcertId(Long concertId, Pageable pageable);

    Optional<Seat> findByIdAndConcertId(Long id, Long concertId);
}
