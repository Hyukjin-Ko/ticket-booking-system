import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, bearer, COMMON_THRESHOLDS } from './lib/config.js';
import { randomUsername, signupAndLogin } from './lib/auth.js';
import { fetchConcerts, fetchSeats } from './lib/setup.js';

export const options = {
  vus: 10,
  duration: '30s',
  thresholds: COMMON_THRESHOLDS,
};

export default function () {
  const username = randomUsername('smoke');
  const token = signupAndLogin(username);
  check(token, { 'token issued': (t) => !!t });

  const concerts = fetchConcerts(token);
  check(concerts, { 'concerts >= 1': (c) => c.length >= 1 });

  if (concerts.length > 0) {
    const seats = fetchSeats(token, concerts[0].id);
    check(seats, { 'seats >= 1': (s) => s.length >= 1 });
  }

  sleep(1);
}
