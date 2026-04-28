package com.harness.ticket.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concert_id", nullable = false)
    private Long concertId;

    @Column(nullable = false, length = 20)
    private String section;

    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "col_no", nullable = false)
    private int colNo;

    private Seat(Long concertId, String section, int rowNo, int colNo) {
        this.concertId = concertId;
        this.section = section;
        this.rowNo = rowNo;
        this.colNo = colNo;
    }

    public static Seat create(Long concertId, String section, int rowNo, int colNo) {
        return new Seat(concertId, section, rowNo, colNo);
    }
}
