import http from 'k6/http';
import { fail } from 'k6';
import { Counter } from 'k6/metrics';

const success200 = new Counter('scenario_t1_status_200');
const busy409or429 = new Counter('scenario_t1_status_busy');
const other = new Counter('scenario_t1_status_other');

const runId = __ENV.RUN_ID || `manual-${Date.now()}`;
const baseUrl = __ENV.RWA_BASE_URL;
const bearer = __ENV.T1_BEARER_TOKEN;
const unitId = __ENV.T1_UNIT_ID;
const amountRaw = __ENV.T1_AMOUNT_RAW || '1000000000000';
const unitPriceRaw = __ENV.T1_UNIT_PRICE_RAW || '100000000';

if (!baseUrl) fail('RWA_BASE_URL is required');
if (!bearer) fail('T1_BEARER_TOKEN is required');
if (!unitId) fail('T1_UNIT_ID is required');

export const options = {
  vus: 2,
  iterations: 2,
  thresholds: {
    scenario_t1_status_200: ['count==1'],
    scenario_t1_status_busy: ['count==1'],
    scenario_t1_status_other: ['count==0'],
  },
};

export default function () {
  const idem = `${runId}-T1-list-${__VU}`;
  const body = JSON.stringify({
    unitId,
    amount: amountRaw,
    unitPrice: unitPriceRaw,
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
  } else if (res.status === 409 || res.status === 429) {
    busy409or429.add(1);
  } else {
    other.add(1);
  }
}
