#!/usr/bin/env bash
# The whole chain, end to end: orders on matching-engine → drop copy → Kafka → ledger.
# Run against `docker compose -f docker-compose.yml -f docker-compose.exchange.yml up`.
#
#   1. three participants with cash and SAP shares in the ledger, as accounts in the engine;
#   2. crossing orders on the engine, while killing the drop copy and later matchd (kill -9);
#   3. every trade the engine reported must end up SETTLED in the ledger, and each
#      participant's SAP position must equal what the engine's fills say: a trade
#      settled twice or lost would show up there.
#
#   scripts/exchange_e2e.sh [ledger URL] [engine URL]     (needs curl and jq)
set -euo pipefail

LEDGER=${1:-http://localhost:8080}
ENGINE=${2:-http://localhost:8090}
COMPOSE=${COMPOSE:-docker compose -f docker-compose.yml -f docker-compose.exchange.yml}
BASE=$(($(date +%s) % 100000 * 10))
A=$((BASE + 1)) B=$((BASE + 2)) C=$((BASE + 3))
WORK=$(mktemp -d)
EXPECTED="$WORK/expected.txt"
: >"$EXPECTED"
POS_A=0 POS_B=0 POS_C=0 # SAP shares bought minus sold, from the engine's fills

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
post_json() { curl -fsS -X POST "$LEDGER$1" -H 'Content-Type: application/json' -d "$2"; }

wait_engine() {
  for _ in $(seq 60); do curl -fsS "$ENGINE/readyz" >/dev/null 2>&1 && return; sleep 1; done
  fail "matching-engine is not ready"
}

open_participant() { # id → funds 1,000,000.00 EUR and 10,000 SAP
  local iban
  iban=$(post_json /api/participants "{\"id\":\"$1\",\"name\":\"Trader $1\"}" | jq -r .iban)
  curl -fsS -X POST "$LEDGER/api/sepa/inbound" -H 'Content-Type: application/xml' --data-binary "
<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"><FIToFICstmrCdtTrf>
  <GrpHdr><MsgId>E2E-$1</MsgId></GrpHdr>
  <CdtTrfTxInf><PmtId><EndToEndId>E2E-FUND-$1</EndToEndId></PmtId>
    <IntrBkSttlmAmt Ccy=\"EUR\">1000000.00</IntrBkSttlmAmt>
    <Dbtr><Nm>Funding</Nm></Dbtr><DbtrAcct><Id><IBAN>FR1420041010050500013M02606</IBAN></Id></DbtrAcct>
    <CdtrAcct><Id><IBAN>$iban</IBAN></Id></CdtrAcct></CdtTrfTxInf>
</FIToFICstmrCdtTrf></Document>" | jq -e '.[0].status == "SETTLED"' >/dev/null
  post_json /api/admin/securities-deposits "{\"participantId\":\"$1\",\"symbol\":\"SAP\",\"quantity\":10000}" >/dev/null
}

order() { curl -fsS -X POST "$ENGINE/v1/instruments/SAP/orders" -H 'Content-Type: application/json' -d "$1"; }

move() { # account delta
  case $1 in
    "$A") POS_A=$((POS_A + $2)) ;;
    "$B") POS_B=$((POS_B + $2)) ;;
    "$C") POS_C=$((POS_C + $2)) ;;
  esac
}

cross() { # seller buyer price qty
  local reply filled
  order "{\"side\":\"sell\",\"type\":\"limit\",\"price\":\"$3\",\"quantity\":\"$4\",\"account_id\":$1}" >/dev/null
  reply=$(order "{\"side\":\"buy\",\"type\":\"limit\",\"time_in_force\":\"ioc\",\"price\":\"$3\",\"quantity\":\"$4\",\"account_id\":$2}")
  echo "$reply" | jq -r '.seq as $s | .fills | to_entries[] | "SAP-\($s)-\(.key + 1)"' >>"$EXPECTED"
  filled=$(echo "$reply" | jq -r '[.fills[].quantity | tonumber] | add // 0')
  move "$1" "-$filled"
  move "$2" "$filled"
}

trade_round() { # n price-prefix
  for i in $(seq 1 "$1"); do
    case $((i % 3)) in
      0) cross "$A" "$B" "$2.$((10 + i))" $((i % 7 + 1)) ;;
      1) cross "$B" "$C" "$2.$((10 + i))" $((i % 5 + 1)) ;;
      2) cross "$C" "$A" "$2.$((10 + i))" $((i % 3 + 1)) ;;
    esac
  done
}

sap() { curl -fsS "$LEDGER/api/participants/$1" | jq '[.balances[] | select(.asset == "SAP") | .balance] | add // 0'; }

step "1. participants $A $B $C: cash and shares in the ledger, accounts in the engine"
wait_engine
for p in $A $B $C; do open_participant "$p"; done

step "2. 30 trades with everything running"
trade_round 30 134

step "3. kill -9 the drop copy, 30 trades while it is down, restart it"
$COMPOSE kill -s KILL dropcopy
trade_round 30 135
$COMPOSE up -d dropcopy

step "4. kill -9 matchd, restart it (journal replay), 20 more trades"
$COMPOSE kill -s KILL matchd
$COMPOSE up -d matchd
wait_engine
trade_round 20 136

want=$(wc -l <"$EXPECTED" | tr -d ' ')
step "5. all $want trades settled in the ledger?"
for attempt in $(seq 1 60); do
  pending=0
  while read -r id; do
    status=$(curl -fsS "$LEDGER/api/settlements/$id" 2>/dev/null | jq -r .status || echo MISSING)
    case $status in
      SETTLED) ;;
      FAILED) fail "trade $id failed settlement: $(curl -fsS "$LEDGER/api/settlements/$id")" ;;
      *) pending=$((pending + 1)) ;;
    esac
  done <"$EXPECTED"
  [ "$pending" = 0 ] && break
  echo "attempt $attempt: $pending of $want not settled yet"
  sleep 2
done
[ "$pending" = 0 ] || { $COMPOSE logs --tail=50 dropcopy; fail "$pending trades never settled"; }
echo "all $want trades SETTLED"

step "6. positions in the ledger = positions from the engine's fills"
for p in "$A:$POS_A" "$B:$POS_B" "$C:$POS_C"; do
  id=${p%%:*} delta=${p##*:}
  got=$(sap "$id")
  echo "$id: SAP $got (10000 $delta)"
  [ "$got" = $((10000 + delta)) ] || fail "$id holds $got SAP, the engine's fills say $((10000 + delta))"
done
curl -fsS "$LEDGER/api/admin/invariants" | jq -e .ok >/dev/null || fail "ledger invariant broken"
curl -fsS localhost:9100/metrics | grep -E '^dropcopy_(trades_published|publish_failures|cursor_seq)' || true

printf '\nEXCHANGE E2E PASSED\n'
