import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, bearer } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';

export const options = {
  scenarios: {
    distributed: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 500 },
        { duration: '1m',  target: 1000 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(95)<500'],
    'http_req_duration{name:reserve_distributed}': ['p(95)<500'],
  },
};

const TARGET_CONCERT_ID = 1;

export default function () {
  const username = randomUsername('dist');
  const token = signupAndLogin(username);
  if (!token) return;

  const seatId = Math.floor(Math.random() * 100) + 1;

  const res = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ concertId: TARGET_CONCERT_ID, seatId }),
    { headers: bearer(token), tags: { name: 'reserve_distributed' } }
  );
  check(res, {
    'reserve 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
  sleep(0.1);
}
