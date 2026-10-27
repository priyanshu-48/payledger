#!/usr/bin/env bash
# Creates the public demo data through the site's own API (run it on the VM or anywhere that can reach the site):
# a MERCHANT user "demo" whose password is published on purpose (it can only see and operate its own payments, 30
# payments a minute), its ledger account, a funded payer wallet, and a few payments in different states.
#
#   deploy/seed-demo.sh            reads PUBLIC_HOST and AUTH_ADMIN_PASSWORD from .env (INSECURE=1: accept Caddy's local certificate)
# Safe to run twice: when the demo user already has payments it does nothing; a run that died half way is completed.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
BASE=${BASE_URL:-https://$PUBLIC_HOST}
[ "${INSECURE:-0}" = 1 ] && export CURL_OPTS=-k   # only for testing against Caddy's local certificate
CURL="curl -sf ${CURL_OPTS:-}"
DEMO_USER=demo
DEMO_PASSWORD=${DEMO_PASSWORD:-payledger-demo}

export AUTH=$BASE/api/auth
. ops/lib/auth.sh
LEDGER=$BASE/api/ledger
PAYMENTS=$BASE/api/payments
post() { $CURL -X POST "$1" -H 'Content-Type: application/json' "${@:3}" -d "$2"; }
json_id() { sed 's/.*"id":"\([^"]*\)".*/\1/'; }

# done when the demo user can log in AND already has payments (a run that died half way has the user but no data)
if TOKEN=$(auth_login "$DEMO_USER" "$DEMO_PASSWORD" 2>/dev/null) \
   && $CURL "$PAYMENTS/payments?size=1" -H "Authorization: Bearer $TOKEN" | grep -q payerAccountId; then
  echo "demo data already exists: nothing to do"; exit 0
fi

ADMIN=$(admin_token)
AUTHZ="Authorization: Bearer $ADMIN"
create_user "$ADMIN" "$DEMO_USER" "$DEMO_PASSWORD" MERCHANT || true   # may exist from a run that died half way
MTOKEN=$(auth_login "$DEMO_USER" "$DEMO_PASSWORD")

SETTLEMENT=$(post $LEDGER/accounts '{"ownerId":"demo-settlement","type":"SETTLEMENT","currency":"INR"}' -H "$AUTHZ" | json_id)
post $LEDGER/accounts "{\"ownerId\":\"$DEMO_USER\",\"type\":\"MERCHANT\",\"currency\":\"INR\"}" -H "$AUTHZ" >/dev/null || true
PAYER=$(post $LEDGER/accounts '{"ownerId":"demo-payer","type":"USER_WALLET","currency":"INR"}' -H "$AUTHZ" | json_id)
post $LEDGER/transfers "{\"fromAccountId\":\"$SETTLEMENT\",\"toAccountId\":\"$PAYER\",\"amount\":1000000.00,\"currency\":\"INR\"}" \
  -H "Idempotency-Key: demo-fund" -H "$AUTHZ" >/dev/null

pay() { # key amount
  post $PAYMENTS/payments "{\"merchantId\":\"$DEMO_USER\",\"payerAccountId\":\"$PAYER\",\"amount\":$2,\"currency\":\"INR\"}" \
    -H "Authorization: Bearer $MTOKEN" -H "Idempotency-Key: $1" | json_id
}
capture() { post $PAYMENTS/payments/$1/capture '{}' -H "Authorization: Bearer $MTOKEN" >/dev/null; }
refund() { post $PAYMENTS/payments/$1/refund '{}' -H "Authorization: Bearer $MTOKEN" >/dev/null; }

P1=$(pay demo-pay-1 499.00); capture "$P1"
P2=$(pay demo-pay-2 1250.50); capture "$P2"
P3=$(pay demo-pay-3 75.00); capture "$P3"; refund "$P3"
pay demo-pay-4 20.00 >/dev/null    # left authorized, so there is something to capture in the console
pay demo-pay-5 310.00 >/dev/null

echo
echo "Demo data created. For the README:"
echo "  login:            $DEMO_USER / $DEMO_PASSWORD   (role MERCHANT)"
echo "  payer account id: $PAYER"
