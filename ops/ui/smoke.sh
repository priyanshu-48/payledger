#!/usr/bin/env bash
# Admin console smoke test (M9): starts the real stack with the UI, runs the Playwright test against it, tears down.
#
#   ops/ui/smoke.sh             needs Docker, Node, Microsoft Edge (or BROWSER_CHANNEL=chrome) and a .env
#   KEEP=1 ops/ui/smoke.sh      leave the stack running (UI: http://localhost:8085)
#
# Uses its own compose project (payledger-ui) with fresh volumes: your normal stack is not touched.
set -euo pipefail

cd "$(dirname "$0")/../.."
[ -f .env ] || { echo "no .env found: cp .env.example .env first"; exit 2; }
set -a; . ./.env; set +a

# The test logs in a handful of times from one address: lift the login limit for this run only. Fast, fault-free bank.
export AUTH_LOGIN_RATE_PER_MINUTE=100000 TRANSFER_RATE_PER_MINUTE=100000 PAYMENT_RATE_PER_MINUTE=100000
export MOCK_BANK_LATENCY_MIN_MS=2 MOCK_BANK_LATENCY_MAX_MS=20
export MOCK_BANK_DECLINE_RATE=0 MOCK_BANK_TIMEOUT_RATE=0 MOCK_BANK_LOST_RESPONSE_RATE=0
P="docker compose -p payledger-ui"
# Where the published ports are: localhost normally, the Docker host when run inside the Jenkins container.
HOST=${STACK_HOST:-localhost}
export UI_URL="http://$HOST:8085"

cleanup() { [ "${KEEP:-0}" = 1 ] || $P down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== starting stack (fresh volumes)"
$P down -v >/dev/null 2>&1 || true
$P up -d --build postgres kafka auth-service payment-service ledger-service admin-ui >/dev/null
for i in $(seq 1 150); do
  ok=1
  for port in 8081 8082 8084; do curl -sf http://$HOST:$port/actuator/health >/dev/null || ok=0; done
  curl -sf "$UI_URL/" >/dev/null || ok=0
  [ $ok = 1 ] && break
  sleep 2
done
[ "$ok" = 1 ] || { echo "stack did not become healthy"; exit 2; }

echo "== playwright"
cd admin-ui
[ -d node_modules ] || npm ci
AUTH_ADMIN_PASSWORD="$AUTH_ADMIN_PASSWORD" npx playwright test
echo
echo "PASS"
