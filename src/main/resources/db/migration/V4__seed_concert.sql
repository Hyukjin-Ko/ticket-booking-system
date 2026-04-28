INSERT INTO concert (title, starts_at, queue_enabled)
VALUES ('하네스 페스티벌 2026', NOW() + INTERVAL '30 days', false);

-- 100석: section 'A', row 1~10, col 1~10
INSERT INTO seat (concert_id, section, row_no, col_no)
SELECT 1, 'A', r.row_no, c.col_no
FROM generate_series(1, 10) AS r(row_no)
CROSS JOIN generate_series(1, 10) AS c(col_no);
