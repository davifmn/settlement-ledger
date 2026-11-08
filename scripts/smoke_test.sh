#!/usr/bin/env bash
# End-to-end check against a running ledger (docker compose or Kubernetes):
# funds two participants with a pacs.008, settles simulated trades that go through Kafka,
# pays out a SEPA transfer idempotently, reconciles a camt.053, and checks the invariants.
#
#   scripts/smoke_test.sh http://localhost:8080
# Needs curl and jq. The ledger must run with ledger.simulator.enabled=true.
set -euo pipefail

BASE=${1:-http://localhost:8080}
RUN=$(date +%s)
A="alice-$RUN"
B="bob-$RUN"
TODAY=$(date -u +%F)

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
post_json() { curl -fsS -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$2"; }
post_xml() { curl -fsS -X POST "$BASE$1" -H 'Content-Type: application/xml' --data-binary "$2"; }

pacs008() { # end-to-end id, amount, creditor IBAN
  cat <<XML
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"><FIToFICstmrCdtTrf>
  <GrpHdr><MsgId>SMOKE-$1</MsgId></GrpHdr>
  <CdtTrfTxInf>
    <PmtId><EndToEndId>$1</EndToEndId></PmtId>
    <IntrBkSttlmAmt Ccy="EUR">$2</IntrBkSttlmAmt>
    <Dbtr><Nm>Smoke Test Funding</Nm></Dbtr>
    <DbtrAcct><Id><IBAN>FR1420041010050500013M02606</IBAN></Id></DbtrAcct>
    <CdtrAcct><Id><IBAN>$3</IBAN></Id></CdtrAcct>
  </CdtTrfTxInf>
</FIToFICstmrCdtTrf></Document>
XML
}

entry() { # end-to-end id, amount, CRDT|DBIT
  echo "<Ntry><Amt Ccy=\"EUR\">$2</Amt><CdtDbtInd>$3</CdtDbtInd><BookgDt><Dt>$TODAY</Dt></BookgDt><NtryDtls><TxDtls><Refs><EndToEndId>$1</EndToEndId></Refs></TxDtls></NtryDtls></Ntry>"
}

step "1. two participants"
IBAN_A=$(post_json /api/participants "{\"id\":\"$A\",\"name\":\"Alice\"}" | jq -r .iban)
IBAN_B=$(post_json /api/participants "{\"id\":\"$B\",\"name\":\"Bob\"}" | jq -r .iban)
echo "$A $IBAN_A / $B $IBAN_B"

step "2. fund them with incoming SEPA credits (pacs.008), twice: the second is a duplicate"
for p in "FUND-A-$RUN 200000.00 $IBAN_A" "FUND-B-$RUN 200000.00 $IBAN_B"; do
  set -- $p
  post_xml /api/sepa/inbound "$(pacs008 "$1" "$2" "$3")" | jq -e '.[0].status == "SETTLED"' >/dev/null || fail "credit $1"
  post_xml /api/sepa/inbound "$(pacs008 "$1" "$2" "$3")" >/dev/null
done
balance=$(curl -fsS "$BASE/api/participants/$A" | jq '.balances[] | select(.asset == "EUR") | .balance')
[ "$balance" = "20000000" ] || fail "expected 200000.00 EUR once, got $balance cents"

step "3. securities deposits"
post_json /api/admin/securities-deposits "{\"participantId\":\"$A\",\"symbol\":\"PETR4\",\"quantity\":20000}" >/dev/null
post_json /api/admin/securities-deposits "{\"participantId\":\"$B\",\"symbol\":\"PETR4\",\"quantity\":20000}" >/dev/null

step "4. 200 trades through Kafka (trades.executed)"
post_json /api/admin/simulate/trades \
  "{\"symbol\":\"PETR4\",\"participants\":[\"$A\",\"$B\"],\"count\":200,\"basePrice\":\"34.15\"}" | jq -c .
for i in $(seq 1 60); do
  done_count=$(curl -fsS "$BASE/api/settlements?limit=500" |
    jq --arg a "$A" '[.[] | select((.buyer == $a or .seller == $a) and .status != "PENDING")] | length')
  [ "$done_count" -ge 200 ] && break
  sleep 1
done
[ "$done_count" -ge 200 ] || fail "only $done_count of 200 trades settled or failed"
curl -fsS "$BASE/api/settlements?limit=500" |
  jq -c --arg a "$A" '[.[] | select(.buyer == $a or .seller == $a)] | group_by(.status) | map({(.[0].status): length}) | add'
shares=$(curl -fsS "$BASE/api/participants" | jq --arg a "$A" --arg b "$B" \
  '[.[] | select(.id == $a or .id == $b) | .balances[] | select(.asset == "PETR4") | .balance] | add')
[ "$shares" = "40000" ] || fail "PETR4 was created or destroyed: $shares shares instead of 40000"

step "5. outbound SEPA transfer, sent twice with the same Idempotency-Key"
KEY="smoke-$RUN"
BODY="{\"participantId\":\"$A\",\"amount\":\"1234.56\",\"creditorName\":\"Berlin Office Supplies\",\"creditorIban\":\"DE89370400440532013000\",\"endToEndId\":\"OUT-$RUN\"}"
first=$(curl -fsS -X POST "$BASE/api/transfers" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" | jq -r .id)
replay=$(curl -fsS -D /tmp/smoke-headers -X POST "$BASE/api/transfers" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" | jq -r .id)
[ "$first" = "$replay" ] || fail "the retry created a second transfer"
grep -qi '^idempotent-replayed: true' /tmp/smoke-headers || fail "missing Idempotent-Replayed header"
for i in $(seq 1 30); do
  status=$(curl -fsS "$BASE/api/transfers/$first" | jq -r .status)
  [ "$status" = "SETTLED" ] && break
  sleep 1
done
[ "$status" = "SETTLED" ] || fail "transfer is $status, expected SETTLED"
echo "transfer $first SETTLED"

step "6. reconciliation with the bank statement (camt.053)"
STATEMENT="<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"><BkToCstmrStmt><Stmt><Id>SMOKE-$RUN</Id>
$(entry "FUND-A-$RUN" 200000.00 CRDT) $(entry "FUND-B-$RUN" 200000.00 CRDT) $(entry "OUT-$RUN" 1234.56 DBIT)
</Stmt></BkToCstmrStmt></Document>"
report=$(post_xml /api/reconciliation/camt053 "$STATEMENT")
echo "$report" | jq -c '{matched, breaks: (.breaks | length)}'
echo "$report" | jq -e --arg r "$RUN" '[.breaks[] | select(.endToEndId | test($r))] | length == 0' >/dev/null ||
  fail "this run's transfers do not reconcile"

step "7. invariants and metrics"
curl -fsS "$BASE/api/admin/invariants" | jq -e .ok >/dev/null || fail "ledger invariant broken"
metrics=$(curl -fsS "$BASE/actuator/prometheus")
for metric in ledger_invariant_violations ledger_outbox_backlog ledger_settlements_total ledger_transfers_total; do
  grep -q "^$metric" <<<"$metrics" || fail "metric $metric missing"
done

printf '\nSMOKE TEST PASSED\n'
