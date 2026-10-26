#!/bin/sh
# One server, three databases, one login per service: services never share tables.
set -e
for svc in ledger payments notifications; do
  pw_var=$(echo "${svc}_DB_PASSWORD" | tr a-z A-Z)
  eval pw=\$$pw_var
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" <<SQL
CREATE ROLE ${svc} LOGIN PASSWORD '${pw}';
CREATE DATABASE ${svc} OWNER ${svc};
SQL
done
