#!/usr/bin/env bash
# Load test (M10): Gatling drives transfers and payments at increasing rates against the real stack, one run per rate,
# then the script proves nothing was lost under load and reports the maximum sustainable rate.
#
#   ops/load/run.sh                       needs Docker and a .env (copy .env.example); Microsoft Edge for the screenshot
#   RATES_TRANSFERS=20,40 RATES_PAYMENTS=10,20 STEP_SECONDS=10 ops/load/run.sh    a quick run
#   KEEP=1 ops/load/run.sh                leave the stack running afterwards
#
# Uses its own compose project (payledger-load) with fresh volumes. Everything runs on this machine, so the numbers
# describe this machine (database, Kafka and all services share it), and the bank is the in-process stand-in.
set -euo pipefail

cd "$(dirname "$0")/../.."
[ -f .env ] || { echo "no .env found: cp .env.example .env first"; exit 2; }
set -a; . ./.env; set +a

# One admin and one merchant generate all the load: lift the per-client limits for this run only. Fast, fault-free bank.
export AUTH_LOGIN_RATE_PER_MINUTE=100000 TRANSFER_RATE_PER_MINUTE=100000 PAYMENT_RATE_PER_MINUTE=100000
export MOCK_BANK_LATENCY_MIN_MS=2 MOCK_BANK_LATENCY_MAX_MS=20
export MOCK_BANK_DECLINE_RATE=0 MOCK_BANK_TIMEOUT_RATE=0 MOCK_BANK_LOST_RESPONSE_RATE=0
export STEP_SECONDS=${STEP_SECONDS:-30}
RATES_T=${RATES_TRANSFERS:-200,400,600,800,1000,1200}
RATES_P=${RATES_PAYMENTS:-120,240,360,480,600,720}
P="docker compose -p payledger-load"
OUT=target/load
STARTED=$(date +%s)

cleanup() { [ "${KEEP:-0}" = 1 ] || $P down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT
sql() { $P exec -T postgres psql -U "$1" -d "$2" -tAc "$3"; }

echo "== starting stack (fresh volumes)"
$P down -v >/dev/null 2>&1 || true
$P up -d --build postgres kafka jaeger auth-service payment-service ledger-service notification-service prometheus grafana >/dev/null
for i in $(seq 1 150); do
  ok=1
  for port in 8081 8082 8083 8084; do curl -sf http://localhost:$port/actuator/health >/dev/null || ok=0; done
  curl -sf http://localhost:3000/api/health >/dev/null || ok=0
  [ $ok = 1 ] && break
  sleep 2
done
[ "$ok" = 1 ] || { echo "stack did not become healthy"; exit 2; }

rm -rf "$OUT" load-tests/target; mkdir -p "$OUT"
export SEED_FILE="$PWD/$OUT/seed.txt"   # the first run creates the accounts and wallets, the others reuse them

gatling() { # SIMULATION RATE [LOG SUFFIX]
  local log="$OUT/$1-${3:-$2}.log"
  RATE="$2" ./mvnw -q -f load-tests/pom.xml gatling:test -Dgatling.simulationClass="payledger.$1" >"$log" 2>&1 \
    || { tail -20 "$log"; echo "$1 at $2/s failed"; exit 1; }
}

# The events a step leaves behind (outbox rows, Kafka messages, ledger postings) compete with the next step for the
# same database, so every step starts from a rested system: wait until everything has been published and posted.
drain() {
  for i in $(seq 1 120); do
    UNPUB_P=$(sql payments payments "select count(*) from outbox where published_at is null")
    UNPUB_L=$(sql ledger ledger "select count(*) from outbox where published_at is null")
    EVENTS=$(sql payments payments "select count(*) from outbox")
    PROCESSED=$(sql ledger ledger "select count(*) from processed_events")
    [ "$UNPUB_P" = 0 ] && [ "$UNPUB_L" = 0 ] && [ "$PROCESSED" = "$EVENTS" ] && break
    sleep 2
  done
  sleep 5
}

# One ladder: run each rate from a rested system and stop at the first rate that is over the limit
ladder() { # SIMULATION LABEL RATES
  for r in ${3//,/ }; do
    echo "   $r/s"; drain
    gatling "$1" "$r"
    python ops/load/summarize.py x "$r=$OUT/$1-$r.log" | grep -q "over the limit" && { echo "   over the limit: the ladder stops here"; break; }
  done
}

echo "== warm-up (not measured): the first requests of a fresh JVM are slow and would skew the first step"
STEP_SECONDS=15 gatling TransferSimulation 50 warmup
STEP_SECONDS=15 gatling PaymentSimulation 30 warmup
echo "== transfers: $RATES_T per second, ${STEP_SECONDS}s each"
ladder TransferSimulation transfers "$RATES_T"
echo "== payments: $RATES_P per second, ${STEP_SECONDS}s each"
ladder PaymentSimulation payments "$RATES_P"

echo "== waiting for the events to drain"
drain

CAPTURED=$(sql payments payments "select count(*) from payments where status='CAPTURED'")
CREATED=$(sql payments payments "select count(*) from payments")
ENTRIES=$(sql ledger ledger "select count(*) from journal_entries where type='PAYMENT_CAPTURE'")
DISTINCT=$(sql ledger ledger "select count(distinct external_ref) from journal_entries where type='PAYMENT_CAPTURE'")
TOTAL=$(sql ledger ledger "select coalesce(sum(balance_minor),0) from account_balances")
LOST=$((CAPTURED - DISTINCT)); DUPLICATED=$((ENTRIES - DISTINCT))

echo
echo "== RESULTS (this machine; stand-in bank; Docker VM shared by database, Kafka and all services)"
args_t=(); for r in ${RATES_T//,/ }; do [ -f "$OUT/TransferSimulation-$r.log" ] && args_t+=("$r=$OUT/TransferSimulation-$r.log"); done
args_p=(); for r in ${RATES_P//,/ }; do [ -f "$OUT/PaymentSimulation-$r.log" ] && args_p+=("$r=$OUT/PaymentSimulation-$r.log"); done
python ops/load/summarize.py "transfers: POST /transfers" "${args_t[@]}" | tee "$OUT/summary.txt"
python ops/load/summarize.py "payments: POST /payments + capture" "${args_p[@]}" | tee -a "$OUT/summary.txt"
{
  echo "== consistency after the load"
  echo "payments created / captured:            $CREATED / $CAPTURED"
  echo "events (outbox) / processed by ledger:  $EVENTS / $PROCESSED   unpublished: $UNPUB_P payment, $UNPUB_L ledger"
  echo "PAYMENT_CAPTURE entries / distinct:     $ENTRIES / $DISTINCT"
  echo "sum of all balances (must be 0):        $TOTAL"
  echo "LOST: $LOST   DUPLICATED: $DUPLICATED"
} | tee -a "$OUT/summary.txt"

EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
if [ -x "$EDGE" ]; then
  MINUTES=$(( ($(date +%s) - STARTED) / 60 + 2 ))
  "$EDGE" --headless --disable-gpu --hide-scrollbars --window-size=1600,1110 --virtual-time-budget=30000 \
    --screenshot="$(cygpath -w "$PWD/docs/img/load-dashboard.png")" \
    "http://localhost:3000/d/payledger/payledger?orgId=1&kiosk&from=now-${MINUTES}m&to=now" >/dev/null 2>&1 || true
fi

echo
if [ "$LOST" = 0 ] && [ "$DUPLICATED" = 0 ] && [ "$TOTAL" = 0 ] && [ "$CAPTURED" = "$ENTRIES" ] && [ "$UNPUB_P" = 0 ]; then
  echo "PASS"
else
  echo "FAIL"; exit 1
fi
