// k6 load test for the gateway. Run the same script twice to get the "with" and "without" limiter numbers:
//   without limiter:  k6 run -e TARGET=http://localhost:9000 bench/gateway.js          (straight to the dummy service)
//   proxy only:       gateway with bench/policies-none.yml  ->  -e TARGET=http://localhost:8080
//   with limiter:     gateway with bench/policies-bench.yml ->  -e TARGET=http://localhost:8080
// Limits in the bench policy are huge, so every request is allowed and we measure overhead, not rejections.
import http from 'k6/http';
import { check } from 'k6';

const TARGET = __ENV.TARGET || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '64');
const DURATION = __ENV.DURATION || '30s';
const KEYS = parseInt(__ENV.KEYS || '1000');

export const options = {
  scenarios: {
    steady: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const key = 'key-' + Math.floor(Math.random() * KEYS);
  const res = http.get(`${TARGET}/ping`, { headers: { 'X-API-Key': key } });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
