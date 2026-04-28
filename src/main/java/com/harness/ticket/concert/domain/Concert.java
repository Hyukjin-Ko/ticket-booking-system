package com.harness.ticket.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "concert")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "queue_enabled", nullable = false)
    private boolean queueEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Concert(String title, Instant startsAt, boolean queueEnabled, Instant createdAt) {
        this.title = title;
        this.startsAt = startsAt;
        this.queueEnabled = queueEnabled;
        this.createdAt = createdAt;
    }

    public static Concert create(String title, Instant startsAt, boolean queueEnabled, Clock clock) {
        return new Concert(title, startsAt, queueEnabled, Instant.now(clock));
    }
}
