package com.harness.ticket.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "concert_id", nullable = false)
    private Long concertId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    private Reservation(Long userId, Long concertId, Long seatId, Instant createdAt) {
        this.userId = userId;
        this.concertId = concertId;
        this.seatId = seatId;
        this.status = ReservationStatus.PENDING;
        this.createdAt = createdAt;
    }

    public static Reservation create(Long userId, Long concertId, Long seatId, Clock clock) {
        return new Reservation(userId, concertId, seatId, Instant.now(clock));
    }

    public void pay(Clock clock) {
        if (status == ReservationStatus.PAID) {
            return;
        }
        if (status == ReservationStatus.CANCELLED) {
            throw new IllegalStateException("이미 취소된 예약은 결제할 수 없습니다");
        }
        this.status = ReservationStatus.PAID;
        this.paidAt = Instant.now(clock);
    }

    public void cancel(Clock clock) {
        if (status == ReservationStatus.CANCELLED) {
            return;
        }
        if (status == ReservationStatus.PAID) {
            throw new IllegalStateException("결제 완료된 예약은 취소할 수 없습니다");
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = Instant.now(clock);
    }
}
