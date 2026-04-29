import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, JSON_HEADERS } from './config.js';

/** 고유 username 생성 (VU+iteration 기반) */
export function randomUsername(prefix = 'k6user') {
  return `${prefix}${__VU}_${__ITER}_${Date.now() % 100000}`;
}

/** signup → login. access 토큰 반환. */
export function signupAndLogin(username, password = 'k6password1') {
  // signup
  const sRes = http.post(
    `${BASE_URL}/auth/signup`,
    JSON.stringify({ username, password }),
    { headers: JSON_HEADERS, tags: { name: 'signup' } }
  );
  check(sRes, { 'signup 201': (r) => r.status === 201 });

  // login
  const lRes = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username, password }),
    { headers: JSON_HEADERS, tags: { name: 'login' } }
  );
  check(lRes, { 'login 200': (r) => r.status === 200 });

  const body = lRes.json();
  return body && body.data ? body.data.accessToken : null;
}
