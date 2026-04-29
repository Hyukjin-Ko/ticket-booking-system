import http from 'k6/http';
import { BASE_URL, bearer } from './config.js';

/** 공연 목록 조회. 토큰 필요. */
export function fetchConcerts(token) {
  const r = http.get(`${BASE_URL}/concerts?size=20`, { headers: bearer(token) });
  return r.json().data.content;
}

/** 좌석 목록 조회. 토큰 필요. */
export function fetchSeats(token, concertId, page = 0, size = 100) {
  const r = http.get(
    `${BASE_URL}/concerts/${concertId}/seats?page=${page}&size=${size}`,
    { headers: bearer(token) }
  );
  return r.json().data.content;
}
