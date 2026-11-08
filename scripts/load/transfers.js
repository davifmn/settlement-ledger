// Load test: a constant arrival rate of outbound SEPA transfers over many participants,
// each with its own Idempotency-Key, plus a retry of every 10th request with the same key.
//
//   k6 run -e BASE_URL=http://localhost:8080 scripts/load/transfers.js
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PARTICIPANTS = 20;
const json = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create}': ['p(95)<300', 'p(99)<800'],
    checks: ['rate>0.99'],
  },
};

function pacs008(e2e, amount, iban) {
  return `<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"><FIToFICstmrCdtTrf>
    <GrpHdr><MsgId>LOAD-${e2e}</MsgId></GrpHdr>
    <CdtTrfTxInf><PmtId><EndToEndId>${e2e}</EndToEndId></PmtId>
      <IntrBkSttlmAmt Ccy="EUR">${amount}</IntrBkSttlmAmt>
      <Dbtr><Nm>Load Test</Nm></Dbtr><DbtrAcct><Id><IBAN>FR1420041010050500013M02606</IBAN></Id></DbtrAcct>
      <CdtrAcct><Id><IBAN>${iban}</IBAN></Id></CdtrAcct></CdtTrfTxInf></FIToFICstmrCdtTrf></Document>`;
}

export function setup() {
  const run = Date.now();
  const ids = [];
  for (let i = 0; i < PARTICIPANTS; i++) {
    const id = `load-${run}-${i}`;
    const created = http.post(`${BASE}/api/participants`, JSON.stringify({ id, name: `Load ${i}` }), json);
    if (created.status !== 201) fail(`could not create ${id}: ${created.status}`);
    const funded = http.post(`${BASE}/api/sepa/inbound`, pacs008(`LOADFUND-${run}-${i}`, '1000000.00', created.json('iban')), {
      headers: { 'Content-Type': 'application/xml' },
    });
    if (funded.status !== 200) fail(`could not fund ${id}`);
    ids.push(id);
  }
  return { ids, run };
}

export default function (data) {
  const participantId = data.ids[(__VU + __ITER) % data.ids.length];
  const key = `load-${data.run}-${__VU}-${__ITER}`;
  const body = JSON.stringify({
    participantId,
    amount: `${1 + (__ITER % 500)}.00`,
    creditorName: 'Load Test Creditor',
    creditorIban: 'DE89370400440532013000',
  });
  const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, tags: { name: 'create' } };

  const res = http.post(`${BASE}/api/transfers`, body, params);
  check(res, { 'created': (r) => r.status === 201 && r.json('status') === 'VALIDATED' });

  if (__ITER % 10 === 0) {
    const retry = http.post(`${BASE}/api/transfers`, body, { ...params, tags: { name: 'retry' } });
    check(retry, {
      'retry returns the same transfer': (r) => r.status === 200 && r.json('id') === res.json('id'),
    });
  }
}
