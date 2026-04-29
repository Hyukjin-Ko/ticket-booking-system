import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, bearer } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';

const successCount = new Counter('seat_reserve_success');
const conflictCount = new Counter('seat_reserve_conflict');

export const options = {
  scenarios: {
    contention: {
      executor: 'per-vu-iterations',
      vus: 1000,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    seat_reserve_success:  ['count==1'],
    seat_reserve_conflict: ['count==999'],
    http_req_failed:       ['rate<0.01'],
  },
};

const TARGET_CONCERT_ID = 1;
const TARGET_SEAT_ID = 1;

export default function () {
  const username = randomUsername('contend');
  const token = signupAndLogin(username);
  if (!token) return;

  const res = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ concertId: TARGET_CONCERT_ID, seatId: TARGET_SEAT_ID }),
    { headers: bearer(token), tags: { name: 'reserve_contended' } }
  );

  if (res.status === 201) {
    successCount.add(1);
  } else if (res.status === 409) {
    conflictCount.add(1);
  }
  check(res, {
    'reserve 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}
