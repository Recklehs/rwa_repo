import http from 'k6/http';
import { fail } from 'k6';
import { Counter } from 'k6/metrics';

const success200 = new Counter('scenario_b4_status_200');
const fail409or400 = new Counter('scenario_b4_status_fail');
const other = new Counter('scenario_b4_status_other');

const runId = __ENV.RUN_ID || `manual-${Date.now()}`;
const baseUrl = __ENV.CRYPTOORDER_BASE_URL;
const accountId = __ENV.B4_ACCOUNT_ID;
const bearer = __ENV.B4_BEARER_TOKEN;
const amount = __ENV.B4_WITHDRAW_AMOUNT || '1000';

if (!baseUrl) fail('CRYPTOORDER_BASE_URL is required');
if (!accountId) fail('B4_ACCOUNT_ID is required');

export const options = {
  vus: 2,
  iterations: 2,
  thresholds: {
    scenario_b4_status_200: ['count==1'],
    scenario_b4_status_fail: ['count==1'],
    scenario_b4_status_other: ['count==0'],
  },
};

export default function () {
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': `${runId}-B4-withdraw-${__VU}`,
  };
  if (bearer) {
    headers.Authorization = `Bearer ${bearer}`;
  }

  const body = JSON.stringify({
    accountId,
    amount,
  });

  const res = http.post(`${baseUrl}/api/bank/withdraw`, body, {
    headers,
    timeout: '30s',
  });

  if (res.status === 200) {
    success200.add(1);
  } else if (res.status === 400 || res.status === 409) {
    fail409or400.add(1);
  } else {
    other.add(1);
  }
}
