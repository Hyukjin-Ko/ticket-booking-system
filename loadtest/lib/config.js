// 공통 설정 — env var로 base URL 주입 가능
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 표준 thresholds (시나리오별로 override 가능)
export const COMMON_THRESHOLDS = {
  http_req_failed:   ['rate<0.01'],     // 에러율 1% 미만
  http_req_duration: ['p(95)<1000'],    // p95 < 1초
};

// 공통 헤더
export const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function bearer(token) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}
