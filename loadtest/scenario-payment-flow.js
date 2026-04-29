import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, bearer } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';

export const options = {
  scenarios: {
    payment: {
      executor: 'constant-vus',
      vus: 100,
      duration: '2m',
    },
  },
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(95)<5000'],
  },
};

const TARGET_CONCERT_ID = 1;

export default function () {
  const username = randomUsername('pay');
  const token = signupAndLogin(username);
  if (!token) return;

  const seatId = Math.floor(Math.random() * 100) + 1;

  const rRes = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ concertId: TARGET_CONCERT_ID, seatId }),
    { headers: bearer(token), tags: { name: 'reserve' } }
  );
  if (rRes.status !== 201) { sleep(0.5); return; }
  const reservationId = rRes.json().data.id;

  const pHeaders = { ...bearer(token), 'X-Mock-Pay-Result': 'success' };
  const pRes = http.post(
    `${BASE_URL}/reservations/${reservationId}/pay`,
    null,
    { headers: pHeaders, tags: { name: 'pay' } }
  );
  check(pRes, { 'pay 200': (r) => r.status === 200 });

  sleep(0.5);
}
