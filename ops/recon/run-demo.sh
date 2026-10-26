#!/usr/bin/env bash
# Reconciliation demo (M6): real payments and captures through the whole stack, then a bank settlement file with
# planted errors is reconciled and the result is checked against the answer key. Also re-submits the same file.
#
#   ops/recon/run-demo.sh [N]       N payments (default 40); needs Docker, python3 and a .env (copy .env.example)
#   KEEP=1 ops/recon/run-demo.sh    leave the stack running afterwards
#
# Uses its own compose project (payledger-recon) with fresh volumes: your normal stack is not touched.
set -euo pipefail

N=${1:-40}
cd "$(dirname "$0")/../.."
[ -f .env ] || { echo "no .env found: cp .env.example .env first"; exit 2; }
set -a; . ./.env; set +a
. ops/lib/auth.sh

# These scripts generate load from one admin and one merchant, which the default per-client rate limits
# (30 a minute, ADR 0019) would throttle; raise them for this run only.
export TRANSFER_RATE_PER_MINUTE=100000 PAYMENT_RATE_PER_MINUTE=100000
export MOCK_BANK_LATENCY_MIN_MS=2 MOCK_BANK_LATENCY_MAX_MS=20
export MOCK_BANK_DECLINE_RATE=0 MOCK_BANK_TIMEOUT_RATE=0 MOCK_BANK_LOST_RESPONSE_RATE=0
PROJECT=payledger-recon
P="docker compose -p $PROJECT"
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
$P up -d --build postgres kafka jaeger auth-service payment-service ledger-service >/dev/null
for i in $(seq 1 120); do
  [ "$($P ps --format '{{.Status}}' | grep -c '(healthy)')" = 5 ] && break
  sleep 2
done
[ "$($P ps --format '{{.Status}}' | grep -c '(healthy)')" = 5 ] || { echo "stack did not become healthy"; exit 2; }

echo "== users: ADMIN token for ledger setup and reconciliation, MERCHANT token for the payments"
ADMIN=$(admin_token)
MERCHANT_PASSWORD=$(new_password)
create_user "$ADMIN" recon-merchant "$MERCHANT_PASSWORD" MERCHANT
MTOKEN=$(auth_login recon-merchant "$MERCHANT_PASSWORD")
AUTHZ="Authorization: Bearer $ADMIN"

echo "== $N funded wallets, one merchant, $N payments created and captured"
SETTLEMENT=$(post $LEDGER/accounts '{"ownerId":"recon-settlement","type":"SETTLEMENT","currency":"INR"}' -H "$AUTHZ" | json_id)
post $LEDGER/accounts '{"ownerId":"recon-merchant","type":"MERCHANT","currency":"INR"}' -H "$AUTHZ" >/dev/null
: > "$WORK/wallets"
for i in $(seq 1 "$N"); do
  W=$(post $LEDGER/accounts "{\"ownerId\":\"recon-wallet-$i\",\"type\":\"USER_WALLET\",\"currency\":\"INR\"}" -H "$AUTHZ" | json_id)
  post $LEDGER/transfers "{\"fromAccountId\":\"$SETTLEMENT\",\"toAccountId\":\"$W\",\"amount\":1000.00,\"currency\":\"INR\"}" \
    -H "Idempotency-Key: recon-fund-$i" -H "$AUTHZ" >/dev/null
  echo "$i $W" >> "$WORK/wallets"
done
pay() {
  local i=$1 w=$2 id
  # a distinct amount per payment (11.00 + i paise) so an amount mismatch is visible
  id=$(post $PAYMENTS/payments "{\"merchantId\":\"recon-merchant\",\"payerAccountId\":\"$w\",\"amount\":$((10 + i)).$(printf '%02d' $((i % 100))),\"currency\":\"INR\"}" \
        -H "Authorization: Bearer $MTOKEN" -H "Idempotency-Key: recon-pay-$i" | json_id)
  curl -sf -X POST "$PAYMENTS/payments/$id/capture" -H "Authorization: Bearer $MTOKEN" >/dev/null
}
export -f pay post json_id
export PAYMENTS MTOKEN
xargs -P 12 -L 1 bash -c 'pay "$0" "$1"' < "$WORK/wallets"

echo "== waiting for the ledger to post every capture"
for i in $(seq 1 90); do
  [ "$(sql ledger ledger "select count(*) from journal_entries where type='PAYMENT_CAPTURE'")" = "$N" ] && break
  sleep 1
done
[ "$(sql ledger ledger "select count(*) from journal_entries where type='PAYMENT_CAPTURE'")" = "$N" ] \
  || { echo "ledger did not post all $N captures"; exit 1; }

echo "== generating the bank settlement file with planted errors"
python ops/recon/recon_tool.py generate --project "$PROJECT" --out "$WORK" --seed 7 --drop 3 --alter 3 --duplicate 2 --extra 3

START=$(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || python -c "import datetime;print((datetime.datetime.utcnow()-datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
END=$(date -u -d '1 day' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || python -c "import datetime;print((datetime.datetime.utcnow()+datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
upload() { curl -s -o "$WORK/run.json" -w '%{http_code}' -H "$AUTHZ" -F "file=@$WORK/settlement.csv" "$LEDGER/recon/runs?periodStart=$START&periodEnd=$END"; }

echo "== reconciling"
CODE1=$(upload)
RUN=$(json_id < "$WORK/run.json")
echo "first upload:  HTTP $CODE1, run $RUN"
cat "$WORK/run.json"; echo
ITEMS1=$(sql ledger ledger "select count(*) from recon_items")

CODE2=$(upload)
RUN2=$(json_id < "$WORK/run.json")
ITEMS2=$(sql ledger ledger "select count(*) from recon_items")
echo "second upload: HTTP $CODE2, run $RUN2, items $ITEMS1 -> $ITEMS2"

echo "== checking against the answer key"
STATUS=0
RECON_TOKEN="$ADMIN" python ops/recon/recon_tool.py verify --run "$RUN" --expected "$WORK/planted-errors.csv" || STATUS=1
{ [ "$CODE1" = 201 ] && [ "$CODE2" = 200 ] && [ "$RUN" = "$RUN2" ] && [ "$ITEMS1" = "$ITEMS2" ]; } || STATUS=1

echo
if [ "$STATUS" = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
