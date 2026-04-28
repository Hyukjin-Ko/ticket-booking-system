CREATE TABLE concert (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200)  NOT NULL,
    starts_at       TIMESTAMPTZ   NOT NULL,
    queue_enabled   BOOLEAN       NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE seat (
    id          BIGSERIAL    PRIMARY KEY,
    concert_id  BIGINT       NOT NULL REFERENCES concert(id) ON DELETE CASCADE,
    section     VARCHAR(20)  NOT NULL,
    row_no      INT          NOT NULL,
    col_no      INT          NOT NULL,
    UNIQUE (concert_id, section, row_no, col_no)
);

CREATE INDEX idx_seat_concert ON seat (concert_id);
