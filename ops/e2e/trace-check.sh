#!/usr/bin/env bash
# End-to-end proof for M5: one payment capture produces ONE distributed trace that spans
# payment-service -> Kafka -> ledger-service -> Kafka -> notification-service, and the mock emails are sent.
#
#   ops/e2e/trace-check.sh            needs Docker and a .env (copy .env.example)
#   KEEP=1 ops/e2e/trace-check.sh     leave the stack running (Jaeger UI at http://localhost:16686)
#
# Uses its own compose project (payledger-e2e) with fresh volumes: your normal stack is not touched.
set -euo pipefail

cd "$(dirname "$0")/../.."
[ -f .env ] || { echo "no .env found: cp .env.example .env first"; exit 2; }
set -a; . ./.env; set +a
. ops/lib/auth.sh

# These scripts generate load from one admin and one merchant, which the default per-client rate limits
# (30 a minute, ADR 0019) would throttle; raise them for this run only.
export TRANSFER_RATE_PER_MINUTE=100000 PAYMENT_RATE_PER_MINUTE=100000
export MOCK_BANK_LATENCY_MIN_MS=2 MOCK_BANK_LATENCY_MAX_MS=20
export MOCK_BANK_DECLINE_RATE=0 MOCK_BANK_TIMEOUT_RATE=0 MOCK_BANK_LOST_RESPONSE_RATE=0
P="docker compose -p payledger-e2e"
LEDGER=http://localhost:8082
PAYMENTS=http://localhost:8081
JAEGER=http://localhost:16686

cleanup() { [ "${KEEP:-0}" = 1 ] || $P down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

sql() { $P exec -T postgres psql -U "$1" -d "$2" -tAc "$3"; }
json_id() { sed 's/.*"id":"\([^"]*\)".*/\1/'; }
post() { curl -sf -X POST "$1" -H 'Content-Type: application/json' "${@:3}" -d "$2"; }

echo "== starting stack (fresh volumes)"
$P down -v >/dev/null 2>&1 || true
$P up -d --build postgres kafka jaeger auth-service payment-service ledger-service notification-service >/dev/null
for i in $(seq 1 120); do
  [ "$($P ps --format '{{.Status}}' | grep -c '(healthy)')" = 6 ] && break
  sleep 2
done
[ "$($P ps --format '{{.Status}}' | grep -c '(healthy)')" = 6 ] || { echo "stack did not become healthy"; exit 2; }

echo "== users: ADMIN token for ledger setup, MERCHANT token for the payment"
ADMIN=$(admin_token)
MERCHANT_PASSWORD=$(new_password)
create_user "$ADMIN" e2e-merchant "$MERCHANT_PASSWORD" MERCHANT
MTOKEN=$(auth_login e2e-merchant "$MERCHANT_PASSWORD")
AUTHZ="Authorization: Bearer $ADMIN"

echo "== one funded wallet, one merchant, one captured payment"
SETTLEMENT=$(post $LEDGER/accounts '{"ownerId":"e2e-settlement","type":"SETTLEMENT","currency":"INR"}' -H "$AUTHZ" | json_id)
post $LEDGER/accounts '{"ownerId":"e2e-merchant","type":"MERCHANT","currency":"INR"}' -H "$AUTHZ" >/dev/null
WALLET=$(post $LEDGER/accounts '{"ownerId":"e2e-alice","type":"USER_WALLET","currency":"INR"}' -H "$AUTHZ" | json_id)
post $LEDGER/transfers "{\"fromAccountId\":\"$SETTLEMENT\",\"toAccountId\":\"$WALLET\",\"amount\":1000.00,\"currency\":\"INR\"}" \
  -H 'Idempotency-Key: e2e-fund' -H "$AUTHZ" >/dev/null
PAYMENT=$(post $PAYMENTS/payments "{\"merchantId\":\"e2e-merchant\",\"payerAccountId\":\"$WALLET\",\"amount\":25.00,\"currency\":\"INR\"}" \
  -H "Authorization: Bearer $MTOKEN" -H 'Idempotency-Key: e2e-pay-1' | json_id)
curl -sf -X POST "$PAYMENTS/payments/$PAYMENT/capture" -H "Authorization: Bearer $MTOKEN" >/dev/null

echo "== waiting for the mock emails (funding: 2, capture: 2)"
for i in $(seq 1 60); do
  [ "$(sql notifications notifications "select count(*) from notifications")" -ge 4 ] && break
  sleep 1
done
sql notifications notifications "select recipient || ' | ' || subject from notifications order by created_at" | sed 's/^/   /'

echo "== looking for one trace that contains all three services (Jaeger)"
FOUND=""
for i in $(seq 1 40); do
  FOUND=$(curl -sf "$JAEGER/api/traces?service=payment-service&limit=50&lookback=1h" | python -c '
import json, sys
need = {"payment-service", "ledger-service", "notification-service"}
for trace in json.load(sys.stdin)["data"]:
    services = {trace["processes"][s["processID"]]["serviceName"] for s in trace["spans"]}
    if need <= services:
        print(trace["traceID"], len(trace["spans"]), ",".join(sorted(services)))
        break
' 2>/dev/null || true)
  [ -n "$FOUND" ] && break
  sleep 3
done

echo
if [ -n "$FOUND" ]; then
  set -- $FOUND
  echo "trace $1 has $2 spans across: $3"
  echo "open $JAEGER/trace/$1"
  echo "log lines with that trace id:"
  $P logs ledger-service notification-service payment-service 2>/dev/null | grep "$1" | sed 's/^/   /' | head -8
  echo "PASS"
else
  echo "no single trace spans payment, ledger and notification"
  echo "FAIL"; exit 1
fi
