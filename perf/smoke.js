import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * Lightweight k6 performance smoke test — establishes response-time / error-rate baselines
 * for the always-available endpoints. NOT a full production load test (see
 * docs/testing/TESTING_STRATEGY.md §Performance for the expansion path).
 *
 * Run:  k6 run perf/smoke.js   (override with PERF_BASE_URL / PERF_VUS / PERF_DURATION)
 */
const BASE = __ENV.PERF_BASE_URL || 'http://localhost:8080';

export const options = {
  vus: Number(__ENV.PERF_VUS || 5),
  duration: __ENV.PERF_DURATION || '30s',
  thresholds: {
    // Baselines — tune as real data comes in.
    http_req_duration: ['p(95)<800'], // 95th percentile under 800ms
    http_req_failed: ['rate<0.01'], // fewer than 1% failed requests
    checks: ['rate>0.99'],
  },
};

export default function () {
  const health = http.get(`${BASE}/api/public/health`);
  check(health, {
    'health returns 200': (r) => r.status === 200,
    'health under 500ms': (r) => r.timings.duration < 500,
  });

  // Auth endpoint should reject fast (validates the login path is responsive under load).
  const login = http.post(
    `${BASE}/login`,
    JSON.stringify({ email: 'nobody@example.com', password: 'wrong' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(login, {
    'login responds (401/400)': (r) => r.status === 401 || r.status === 400,
    'login under 800ms': (r) => r.timings.duration < 800,
  });

  sleep(1);
}
