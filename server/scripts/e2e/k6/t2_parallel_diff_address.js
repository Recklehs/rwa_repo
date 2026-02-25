import http from 'k6/http';
import { fail } from 'k6';
import { Counter } from 'k6/metrics';

const success200 = new Counter('scenario_t2_status_200');
const other = new Counter('scenario_t2_status_other');

const runId = __ENV.RUN_ID || `manual-${Date.now()}`;
const baseUrl = __ENV.RWA_BASE_URL;

const tokenA = __ENV.T2_BEARER_TOKEN_A;
const tokenB = __ENV.T2_BEARER_TOKEN_B;
const unitA = __ENV.T2_UNIT_ID_A;
const unitB = __ENV.T2_UNIT_ID_B;

if (!baseUrl) fail('RWA_BASE_URL is required');
if (!tokenA || !tokenB) fail('T2_BEARER_TOKEN_A/B are required');
if (!unitA || !unitB) fail('T2_UNIT_ID_A/B are required');

export const options = {
  vus: 2,
  iterations: 2,
  thresholds: {
    scenario_t2_status_200: ['count==2'],
    scenario_t2_status_other: ['count==0'],
  },
};

export default function () {
  const isA = __VU % 2 === 1;
  const bearer = isA ? tokenA : tokenB;
  const unitId = isA ? unitA : unitB;
  const idem = `${runId}-T2-list-${isA ? 'A' : 'B'}-${__VU}`;

  const body = JSON.stringify({
    unitId,
    amount: '1000000000000',
    unitPrice: '100000000',
  });

  const res = http.post(`${baseUrl}/trade/list`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${bearer}`,
      'Idempotency-Key': idem,
    },
    timeout: '60s',
  });

  if (res.status === 200) {
    success200.add(1);
  } else {
    other.add(1);
  }
}
