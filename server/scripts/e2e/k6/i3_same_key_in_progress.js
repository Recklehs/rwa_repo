import http from 'k6/http';
import { fail } from 'k6';
import { Counter } from 'k6/metrics';

const status200 = new Counter('scenario_i3_status_200');
const status202 = new Counter('scenario_i3_status_202');
const statusOther = new Counter('scenario_i3_status_other');

const runId = __ENV.RUN_ID || `manual-${Date.now()}`;
const baseUrl = __ENV.RWA_BASE_URL;
const serviceToken = __ENV.SERVICE_TOKEN;
const userId = __ENV.I3_USER_ID;

if (!baseUrl) fail('RWA_BASE_URL is required');
if (!serviceToken) fail('SERVICE_TOKEN is required');
if (!userId) fail('I3_USER_ID is required');

const idempotencyKey = `${runId}-I3-in-progress`;
const payload = JSON.stringify({
  userId,
  provider: 'MEMBER',
  externalUserId: `${runId}-i3-user`,
});

export const options = {
  vus: 2,
  iterations: 2,
  thresholds: {
    scenario_i3_status_200: ['count==1'],
    scenario_i3_status_202: ['count==1'],
    scenario_i3_status_other: ['count==0'],
  },
};

export default function () {
  const res = http.post(`${baseUrl}/internal/wallets/provision`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Service-Token': serviceToken,
      'Idempotency-Key': idempotencyKey,
    },
    timeout: '30s',
  });

  if (res.status === 200) {
    status200.add(1);
  } else if (res.status === 202) {
    status202.add(1);
  } else {
    statusOther.add(1);
  }
}
