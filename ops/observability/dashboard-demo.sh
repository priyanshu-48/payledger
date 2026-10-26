#!/usr/bin/env bash
# Dashboard demo (M8): drives real traffic and failures through the whole stack so every panel of the Grafana
# dashboard has something to show, checks that every dashboard query returns data, and takes docs/img/dashboard.png.
#
#   ops/observability/dashboard-demo.sh [N]    N payments per batch (default 80); needs Docker, python, Microsoft Edge
#   KEEP=1 ops/observability/dashboard-demo.sh leave the stack running (Grafana: http://localhost:3000, no login)
#
# What happens: payments and captures with the default bank faults; Kafka is stopped for a while (the payment
# outbox lag builds up); ledger-service is killed while payments continue (its consumer lag builds up); a
# reconciliation run with planted errors; a burst of bad logins that trips the login rate limit (429).
# Uses its own compose project (payledger-obs) with fresh volumes: your normal stack is not touched.
set -euo pipefail

N=${1:-80}
cd "$(dirname "$0")/../.."
[ -f .env ] || { echo "no .env found: cp .env.example .env first"; exit 2; }
set -a; . ./.env; set +a
. ops/lib/auth.sh

# One admin and one merchant generate the load: lift the per-client limits for transfers and payments, but leave
# the login limit alone (the burst at the end uses it to produce 429s). Fast bank, default fault rates.
export TRANSFER_RATE_PER_MINUTE=100000 PAYMENT_RATE_PER_MINUTE=100000
export MOCK_BANK_LATENCY_MIN_MS=20 MOCK_BANK_LATENCY_MAX_MS=300
export STATUS_CHECK_GRACE=PT5S STATUS_CHECK_INTERVAL=PT5S
PROJECT=payledger-obs
P="docker compose -p $PROJECT"
LEDGER=http://localhost:8082
PAYMENTS=http://localhost:8081
WORK=$(mktemp -d)
STARTED=$(date +%s)

cleanup() { [ "${KEEP:-0}" = 1 ] || $P down -v >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

sql() { $P exec -T postgres psql -U "$1" -d "$2" -tAc "$3"; }
json_id() { sed 's/.*"id":"\([^"]*\)".*/\1/'; }
post() { curl -sf -X POST "$1" -H 'Content-Type: application/json' "${@:3}" -d "$2"; }
# query_prom EXPR -> first value of an instant query (empty when there is none)
query_prom() { curl -sG http://localhost:9090/api/v1/query --data-urlencode "query=$1" |
  sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p'; }

echo "== starting stack (fresh volumes)"
$P down -v >/dev/null 2>&1 || true
$P up -d --build postgres kafka jaeger auth-service payment-service ledger-service notification-service prometheus grafana >/dev/null
for i in $(seq 1 150); do
  ok=1
  for port in 8081 8082 8083 8084; do
    curl -sf http://localhost:$port/actuator/health >/dev/null || ok=0
  done
  curl -sf http://localhost:3000/api/health >/dev/null || ok=0
  [ $ok = 1 ] && break
  sleep 2
done
[ "$ok" = 1 ] || { echo "stack did not become healthy"; exit 2; }

echo "== users and accounts"
ADMIN=$(admin_token)
MERCHANT_PASSWORD=$(new_password)
create_user "$ADMIN" obs-merchant "$MERCHANT_PASSWORD" MERCHANT
MTOKEN=$(auth_login obs-merchant "$MERCHANT_PASSWORD")
AUTHZ="Authorization: Bearer $ADMIN"
SETTLEMENT=$(post $LEDGER/accounts '{"ownerId":"obs-settlement","type":"SETTLEMENT","currency":"INR"}' -H "$AUTHZ" | json_id)
post $LEDGER/accounts '{"ownerId":"obs-merchant","type":"MERCHANT","currency":"INR"}' -H "$AUTHZ" >/dev/null
: > "$WORK/wallets"
for i in $(seq 1 $((N * 3))); do
  W=$(post $LEDGER/accounts "{\"ownerId\":\"obs-wallet-$i\",\"type\":\"USER_WALLET\",\"currency\":\"INR\"}" -H "$AUTHZ" | json_id)
  post $LEDGER/transfers "{\"fromAccountId\":\"$SETTLEMENT\",\"toAccountId\":\"$W\",\"amount\":1000.00,\"currency\":\"INR\"}" \
    -H "Idempotency-Key: obs-fund-$i" -H "$AUTHZ" >/dev/null
  echo "$i $W" >> "$WORK/wallets"
done
# same funding key again: shows up as "replayed" in the transfer outcomes
post $LEDGER/transfers "{\"fromAccountId\":\"$SETTLEMENT\",\"toAccountId\":\"$(sed -n 1p "$WORK/wallets" | cut -d' ' -f2)\",\"amount\":1000.00,\"currency\":\"INR\"}" \
  -H "Idempotency-Key: obs-fund-1" -H "$AUTHZ" >/dev/null

pay() { # $1 index, $2 wallet: create, then capture when the bank authorized it (declines and timeouts stay as they are)
  local i=$1 w=$2 id
  id=$(post $PAYMENTS/payments "{\"merchantId\":\"obs-merchant\",\"payerAccountId\":\"$w\",\"amount\":10.00,\"currency\":\"INR\"}" \
        -H "Authorization: Bearer $MTOKEN" -H "Idempotency-Key: obs-pay-$i" | json_id) || return 0
  curl -s -X POST "$PAYMENTS/payments/$id/capture" -H "Authorization: Bearer $MTOKEN" >/dev/null || true
}
export -f pay post json_id
export PAYMENTS MTOKEN
xfer() { # KEY FROM TO AMOUNT
  post $LEDGER/transfers "{\"fromAccountId\":\"$2\",\"toAccountId\":\"$3\",\"amount\":$4,\"currency\":\"INR\"}" \
    -H "Idempotency-Key: $1" -H "$AUTHZ" >/dev/null 2>&1 || true
}
# Peer transfers over HTTP (what ledger.transfer.duration measures), with some replays (same key) and some refusals.
peer() { # $1 label
  local i from to
  for i in $(seq 1 40); do
    from=$(sed -n "${i}p" "$WORK/wallets" | cut -d' ' -f2); to=$(sed -n "$((i + 1))p" "$WORK/wallets" | cut -d' ' -f2)
    xfer "obs-$1-$i" "$from" "$to" 1.00
    if [ $((i % 8)) = 0 ]; then xfer "obs-$1-$i" "$from" "$to" 1.00; fi
    if [ $((i % 10)) = 0 ]; then xfer "obs-$1-big-$i" "$from" "$to" 999999.00; fi
  done
}
batch() { sed -n "$1,$2p" "$WORK/wallets" | xargs -P 12 -L 1 bash -c 'pay "$0" "$1"'; }

echo "== batch A: $N payments with everything healthy"
batch 1 "$N"
peer a
sleep 20

echo "== batch B: Kafka is down, so the payment outbox cannot publish (outbox lag grows)"
$P stop kafka >/dev/null
batch $((N + 1)) $((N * 2))
sleep 40
echo "   unpublished outbox rows: $(sql payments payments "select count(*) from outbox where published_at is null")"

echo "== batch C: Kafka is back, but ledger-service is killed while payments continue (consumer lag grows)"
$P up -d kafka >/dev/null
for i in $(seq 1 60); do $P exec -T kafka sh -c 'true' >/dev/null 2>&1 && break; sleep 1; done
sleep 25                                         # let the poller drain the backlog onto the topic
$P kill ledger-service >/dev/null
batch $((N * 2 + 1)) $((N * 3))
sleep 20
$P up -d ledger-service >/dev/null
for i in $(seq 1 90); do curl -sf http://localhost:8082/actuator/health >/dev/null && break; sleep 2; done

peer b

echo "== waiting for the ledger to catch up"
for i in $(seq 1 120); do
  [ "$(sql payments payments "select count(*) from outbox where published_at is null")" = 0 ] &&
  [ "$(sql ledger ledger "select count(*) from processed_events")" = "$(sql payments payments "select count(*) from outbox")" ] && break
  sleep 2
done

echo "== reconciliation with planted errors"
python ops/recon/recon_tool.py generate --project "$PROJECT" --out "$WORK" --seed 7 --drop 4 --alter 3 --duplicate 2 --extra 3
START=$(python -c "import datetime;print((datetime.datetime.now(datetime.UTC)-datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
END=$(python -c "import datetime;print((datetime.datetime.now(datetime.UTC)+datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
CODE=$(curl -s -o "$WORK/run.json" -w '%{http_code}' -H "$AUTHZ" -F "file=@$WORK/settlement.csv" "$LEDGER/recon/runs?periodStart=$START&periodEnd=$END")
echo "   HTTP $CODE: $(cat "$WORK/run.json")"

echo "== a burst of wrong logins trips the login rate limit"
for i in $(seq 1 30); do
  curl -s -o /dev/null -X POST http://localhost:8084/auth/token -H 'Content-Type: application/json' \
    -d '{"username":"nobody","password":"wrong"}'
done

peer c
echo "== waiting for Prometheus to scrape the results"
sleep 40

echo "== checking every dashboard query against Prometheus"
STATUS=0
python ops/observability/check_dashboard.py ops/grafana/dashboards/payledger.json http://localhost:9090 || STATUS=1

echo "== screenshot"
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
if [ -x "$EDGE" ]; then
  MINUTES=$(( ($(date +%s) - STARTED) / 60 + 2 ))
  mkdir -p docs/img
  "$EDGE" --headless --disable-gpu --hide-scrollbars --window-size=1600,1110 --virtual-time-budget=30000 \
    --screenshot="$(cygpath -w "$PWD/docs/img/dashboard.png")" \
    "http://localhost:3000/d/payledger/payledger?orgId=1&kiosk&from=now-${MINUTES}m&to=now" >/dev/null 2>&1 || true
  [ -s docs/img/dashboard.png ] && echo "   wrote docs/img/dashboard.png" || { echo "   no screenshot written"; STATUS=1; }
else
  echo "   Microsoft Edge not found: open http://localhost:3000/d/payledger (use KEEP=1) and capture it yourself"
fi

echo
if [ "$STATUS" = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
