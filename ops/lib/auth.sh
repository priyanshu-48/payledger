#!/usr/bin/env bash
# Helpers for the ops scripts to authenticate against the secured stack (source this file; ADR 0018).
# Needs AUTH_ADMIN_PASSWORD in the environment (the scripts load it from .env) and auth-service on :8084.
AUTH=${AUTH:-http://localhost:8084}

# auth_login USERNAME PASSWORD -> prints an access token
auth_login() {
  curl -sf ${CURL_OPTS:-} -X POST "$AUTH/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/'
}

# admin_token -> prints a token for the bootstrap ADMIN user
admin_token() { auth_login admin "$AUTH_ADMIN_PASSWORD"; }

# create_user ADMIN_TOKEN USERNAME PASSWORD ROLE
create_user() {
  curl -sf ${CURL_OPTS:-} -X POST "$AUTH/auth/users" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$2\",\"password\":\"$3\",\"role\":\"$4\"}" >/dev/null
}

# new_password -> a random password that satisfies the policy (never printed to logs by the scripts)
new_password() { head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
