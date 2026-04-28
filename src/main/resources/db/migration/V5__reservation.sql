CREATE TABLE reservation (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    concert_id    BIGINT       NOT NULL REFERENCES concert(id),
    seat_id       BIGINT       NOT NULL REFERENCES seat(id),
    status        VARCHAR(20)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    paid_at       TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ
);

CREATE INDEX idx_reservation_status_created ON reservation (status, created_at);
CREATE INDEX idx_reservation_user           ON reservation (user_id);
