package com.harness.ticket.reservation.repository;

import com.harness.ticket.reservation.domain.Reservation;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Query(value = "SELECT * FROM reservation "
            + "WHERE status = 'PENDING' AND created_at < :cutoff "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Reservation> findStaleForUpdate(@Param("cutoff") Instant cutoff);
}
