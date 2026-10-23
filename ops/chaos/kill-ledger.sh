#!/usr/bin/env bash
# Chaos test (SPEC section 7): kill ledger-service with events in flight, restart it, and prove that every
# payment capture reached the ledger exactly once: 0 lost, 0 duplicated postings.
#
#   ops/chaos/kill-ledger.sh [N]      N payments (default 300); needs Docker and a .env (copy .env.example)
#   KEEP=1 ops/chaos/kill-ledger.sh   leave the stack running afterwards for inspection
#
# Uses its own compose project (payledger-chaos): your normal stack and data are not touched.
set -euo pipefail

N=${1:-300}
cd "$(dirname "$0")/../.."
[ -f .env ] || { echo "no .env found: cp .env.example .env first"; exit 2; }

export MOCK_BANK_LATENCY_MIN_MS=2 MOCK_BANK_LATENCY_MAX_MS=20
export MOCK_BANK_DECLINE_RATE=0 MOCK_BANK_TIMEOUT_RATE=0 MOCK_BANK_LOST_RESPONSE_RATE=0
P="docker compose -p payledger-chaos"
LEDGER=http://localhost:8082
PAYMENTS=http://localhost:8081
WORK=$(mktemp -d)

cleanup() { [ "${KEEP:-0}" = 1 ] || $P down -v >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

sql() { $P exec -T postgres psql -U "$1" -d "$2" -tAc "$3"; }
json_id() { sed 's/.*"id":"\([^"]*\)".*/\1/'; }
post() { curl -sf -X POST "$1" -H 'Content-Type: application/json' "${@:3}" -d "$2"; }

echo "== starting stack (fresh volumes)"
$P down -v >/dev/null 2>&1 || true
$P up -d --build postgres kafka payment-service ledger-service >/dev/null
for i in $(seq 1 90); do
  [ "$($P ps --format '{{.Status}}' | grep -c '(healthy)')" = 4 ] && break
  sleep 2
done
[ "$($P ps --format '{{.Status}}' | grep -c '(healthy)')" = 4 ] || { echo "stack did not become healthy"; exit 2; }

echo "== ledger setup: settlement, merchant, $N funded wallets"
SETTLEMENT=$(post $LEDGER/accounts '{"ownerId":"chaos-settlement","type":"SETTLEMENT","currency":"INR"}' | json_id)
post $LEDGER/accounts '{"ownerId":"chaos-merchant","type":"MERCHANT","currency":"INR"}' >/dev/null
: > "$WORK/wallets"
for i in $(seq 1 "$N"); do
  W=$(post $LEDGER/accounts "{\"ownerId\":\"chaos-wallet-$i\",\"type\":\"USER_WALLET\",\"currency\":\"INR\"}" | json_id)
  post $LEDGER/transfers "{\"fromAccountId\":\"$SETTLEMENT\",\"toAccountId\":\"$W\",\"amount\":1000.00,\"currency\":\"INR\"}" \
    -H "Idempotency-Key: chaos-fund-$i" >/dev/null
  echo "$i $W" >> "$WORK/wallets"
done

pay() { # $1 index, $2 wallet id: create then capture one 10.00 payment
  local i=$1 w=$2 id
  id=$(post $PAYMENTS/payments "{\"merchantId\":\"chaos-merchant\",\"payerAccountId\":\"$w\",\"amount\":10.00,\"currency\":\"INR\"}" \
        -H 'X-Client-Id: chaos' -H "Idempotency-Key: chaos-pay-$i" | json_id)
  curl -sf -X POST "$PAYMENTS/payments/$id/capture" >/dev/null
}
export -f pay post json_id
export PAYMENTS

echo "== creating and capturing $N payments (12 in parallel)"
xargs -P 12 -L 1 bash -c 'pay "$0" "$1"' < "$WORK/wallets" &
PAYERS=$!

while [ "$(sql payments payments "select count(*) from payments where status='CAPTURED'")" -lt $((N / 3)) ]; do sleep 0.3; done
BEFORE_KILL=$(sql ledger ledger "select count(*) from processed_events")
echo "== KILLING ledger-service (docker kill) with $BEFORE_KILL events processed so far"
$P kill ledger-service >/dev/null
sleep 3
wait "$PAYERS"
echo "== all payments created; ledger still down. unpublished outbox rows: $(sql payments payments "select count(*) from outbox where published_at is null")"

echo "== restarting ledger-service"
$P up -d ledger-service >/dev/null
EVENTS=$(sql payments payments "select count(*) from outbox")
for i in $(seq 1 120); do
  [ "$(sql payments payments "select count(*) from outbox where published_at is null")" = 0 ] &&
  [ "$(sql ledger ledger "select count(*) from processed_events")" = "$EVENTS" ] && break
  sleep 2
done

CAPTURED=$(sql payments payments "select count(*) from payments where status='CAPTURED'")
ENTRIES=$(sql ledger ledger "select count(*) from journal_entries where type='PAYMENT_CAPTURE'")
DISTINCT=$(sql ledger ledger "select count(distinct external_ref) from journal_entries where type='PAYMENT_CAPTURE'")
POSTINGS=$(sql ledger ledger "select count(*) from postings p join journal_entries e on e.id=p.entry_id where e.type='PAYMENT_CAPTURE'")
PROCESSED=$(sql ledger ledger "select count(*) from processed_events")
MERCHANT=$(sql ledger ledger "select b.balance_minor from account_balances b join accounts a on a.id=b.account_id where a.type='MERCHANT'")
TOTAL=$(sql ledger ledger "select coalesce(sum(balance_minor),0) from account_balances")
LOST=$((CAPTURED - DISTINCT)); DUPLICATED=$((ENTRIES - DISTINCT))

echo
echo "== RESULT"
echo "payments captured (payment DB):        $CAPTURED"
echo "events in outbox / processed by ledger: $EVENTS / $PROCESSED   (killed after $BEFORE_KILL)"
echo "PAYMENT_CAPTURE entries / distinct:     $ENTRIES / $DISTINCT"
echo "postings on those entries:              $POSTINGS (expected $((2 * CAPTURED)))"
echo "merchant balance (minor units):         $MERCHANT (expected $((1000 * CAPTURED)))"
echo "sum of all balances (must be 0):        $TOTAL"
echo "LOST: $LOST   DUPLICATED: $DUPLICATED"

if [ "$CAPTURED" = "$N" ] && [ "$LOST" = 0 ] && [ "$DUPLICATED" = 0 ] && [ "$POSTINGS" = $((2 * N)) ] \
   && [ "$PROCESSED" = "$EVENTS" ] && [ "$MERCHANT" = $((1000 * N)) ] && [ "$TOTAL" = 0 ]; then
  echo "PASS"
else
  echo "FAIL"; exit 1
fi
