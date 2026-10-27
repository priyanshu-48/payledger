#!/usr/bin/env bash
# Checks a deployed PayLedger from the outside (ADR 0023): valid HTTPS, the console, the public demo login, a real
# payment, the dashboard, and that nothing that should be private answers.
#
#   ops/deploy/smoke-remote.sh https://203-0-113-7.sslip.io
#   INSECURE=1 ops/deploy/smoke-remote.sh https://localhost      (Caddy's local certificate, for testing the kit on a laptop)
set -uo pipefail

BASE=${1:?usage: smoke-remote.sh <base url>}
BASE=${BASE%/}
HOST=${BASE#*://}; HOST=${HOST%%/*}
DEMO_USER=${DEMO_USER:-demo}
DEMO_PASSWORD=${DEMO_PASSWORD:-payledger-demo}
OPTS=""; [ "${INSECURE:-0}" = 1 ] && OPTS="-k"
FAILS=0

check() { # description, then a command that must succeed
  local what=$1; shift
  if "$@" >/dev/null 2>&1; then echo "  ok    $what"; else echo "  FAIL  $what"; FAILS=$((FAILS + 1)); fi
}
status() { curl -s $OPTS -o /dev/null -w '%{http_code}' --max-time 15 "$@"; }
is() { [ "$(status "${@:2}")" = "$1" ]; }
closed() { ! (timeout 4 bash -c "exec 3<>/dev/tcp/$HOST/$1" 2>/dev/null); }   # nothing listens on that port from outside
json() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }

echo "== $BASE"
check "HTTPS answers with a certificate curl trusts"      is 200 "$BASE/"
check "the console page is PayLedger"                     bash -c "curl -s $OPTS '$BASE/' | grep -q 'PayLedger'"
check "a wrong password is refused (401)"                 is 401 -X POST "$BASE/api/auth/auth/token" -H 'Content-Type: application/json' -d '{"username":"demo","password":"definitely-wrong"}'
check "actuator is not reachable through the proxy"       is 404 "$BASE/api/payments/actuator/health"
check "the mock bank is not reachable through the proxy"  is 404 "$BASE/api/payments/mock-bank/authorizations"
check "the dashboard loads without a login"               is 200 "$BASE/grafana/api/dashboards/uid/payledger"
check "an API call without a token is refused (401)"      is 401 "$BASE/api/payments/payments"

TOKEN=$(curl -s $OPTS -X POST "$BASE/api/auth/auth/token" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$DEMO_USER\",\"password\":\"$DEMO_PASSWORD\"}" | json accessToken)
check "the demo login works"                              test -n "$TOKEN"

if [ -n "$TOKEN" ]; then
  AUTHZ="Authorization: Bearer $TOKEN"
  PAYER=$(curl -s $OPTS "$BASE/api/payments/payments?size=1" -H "$AUTHZ" | json payerAccountId)
  check "the demo merchant sees its seeded payments"      test -n "$PAYER"
  ID=$(curl -s $OPTS -X POST "$BASE/api/payments/payments" -H "$AUTHZ" -H 'Content-Type: application/json' \
        -H "Idempotency-Key: smoke-$(date +%s)-$RANDOM" \
        -d "{\"merchantId\":\"$DEMO_USER\",\"payerAccountId\":\"$PAYER\",\"amount\":1.00,\"currency\":\"INR\"}" | json id)
  check "a payment can be created"                        test -n "$ID"
  check "and captured"                                    bash -c "curl -s $OPTS -X POST '$BASE/api/payments/payments/$ID/capture' -H '$AUTHZ' | grep -q CAPTURED"
  check "the merchant cannot use the admin-only list"     is 403 "$BASE/api/ledger/accounts" -H "$AUTHZ"
fi

for port in 5432 29092 8081 8082 8083 8084 9090 3000 16686; do
  if [ "${INSECURE:-0}" = 1 ]; then break; fi   # on a laptop other stacks may use these ports; the check is for the VM
  check "port $port is closed from outside"               closed "$port"
done

echo
if [ "$FAILS" = 0 ]; then echo "PASS"; else echo "FAIL ($FAILS checks)"; exit 1; fi
