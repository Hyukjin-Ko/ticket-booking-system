import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, bearer } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';

export const options = {
  scenarios: {
    queue_burst: {
      executor: 'per-vu-iterations',
      vus: 5000,
      iterations: 1,
      maxDuration: '120s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:queue_enter}': ['p(95)<500'],
  },
};

const QUEUED_CONCERT_ID = 2;

export default function () {
  const username = randomUsername('qburst');
  const token = signupAndLogin(username);
  if (!token) return;

  const res = http.post(
    `${BASE_URL}/queue/${QUEUED_CONCERT_ID}/enter`,
    null,
    { headers: bearer(token), tags: { name: 'queue_enter' } }
  );
  check(res, { 'enter 200': (r) => r.status === 200 });
}
