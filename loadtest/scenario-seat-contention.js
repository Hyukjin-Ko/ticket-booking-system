import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, bearer } from './lib/config.js';
import { signupAndLogin } from './lib/auth.js';

const successCount = new Counter('seat_reserve_success');
const conflictCount = new Counter('seat_reserve_conflict');

const VU_COUNT = 1000;

export const options = {
  scenarios: {
    contention: {
      executor: 'per-vu-iterations',
      vus: VU_COUNT,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    seat_reserve_success:  ['count==1'],            // ★ 정확히 1
    seat_reserve_conflict: [`count==${VU_COUNT - 1}`], // ★ 정확히 N-1
    http_req_failed:       ['rate<0.01'],
  },
};

const TARGET_CONCERT_ID = 1;
const TARGET_SEAT_ID = 1;

/**
 * setup() — k6 init 단계에서 1회 실행. iteration 시작 전에 모든 사용자 미리 생성.
 * BCrypt CPU 부하를 좌석 경합 측정에서 분리. setup()은 순차 실행이라 auth 부하 없음.
 */
export function setup() {
  console.log(`Pre-creating ${VU_COUNT} users sequentially...`);
  const tokens = [];
  const ts = Date.now() % 100000;
  for (let i = 0; i < VU_COUNT; i++) {
    const username = `contendpre${i}t${ts}`;
    const token = signupAndLogin(username);
    if (token) tokens.push(token);
  }
  console.log(`Created ${tokens.length} / ${VU_COUNT} tokens`);
  if (tokens.length < VU_COUNT) {
    throw new Error(`Only ${tokens.length} users created (expected ${VU_COUNT}). Aborting.`);
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
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
