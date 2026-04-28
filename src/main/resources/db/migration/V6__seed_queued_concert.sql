-- 큐 시연용 공연 (concert id=2, queueEnabled=true)
INSERT INTO concert (title, starts_at, queue_enabled)
VALUES ('하네스 페스티벌 2026 - 인기 공연', NOW() + INTERVAL '30 days', true);

-- 100석
INSERT INTO seat (concert_id, section, row_no, col_no)
SELECT 2, 'A', r.row_no, c.col_no
FROM generate_series(1, 10) AS r(row_no)
CROSS JOIN generate_series(1, 10) AS c(col_no);
