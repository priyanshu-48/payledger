#!/usr/bin/env bash
# Writes a throwaway .env for CI: a random value for every variable that .env.example leaves blank
# (except the optional signing key, which stays empty so auth-service generates one).
set -euo pipefail
cd "$(dirname "$0")/../.."

: > .env
while IFS= read -r line; do
  case "$line" in
    ''|'#'*) continue ;;
    AUTH_JWT_PRIVATE_KEY=*) echo "$line" >> .env ;;
    *=) echo "${line}$(openssl rand -hex 16)" >> .env ;;
    *) echo "$line" >> .env ;;
  esac
done < .env.example
echo "wrote .env with $(grep -c . .env) variables"
