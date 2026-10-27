#!/usr/bin/env bash
# Line coverage of all main code, from the JaCoCo exec files that `./mvnw verify` leaves in each module's target/.
# One number across modules: code in the shared modules (outbox, security, common) counts when a service's tests run it.
#
#   ops/ci/coverage.sh           run after ./mvnw verify
set -euo pipefail
cd "$(dirname "$0")/../.."

MODULES="common outbox security auth-service ledger-service payment-service notification-service"
CLI=target/jacoco-cli/jacococli.jar
if [ ! -f "$CLI" ]; then
  ./mvnw -q dependency:copy -Dartifact=org.jacoco:org.jacoco.cli:0.8.12:jar:nodeps -DoutputDirectory=target/jacoco-cli -Dmdep.stripVersion=true
  mv target/jacoco-cli/org.jacoco.cli-nodeps.jar "$CLI" 2>/dev/null || mv target/jacoco-cli/org.jacoco.cli.jar "$CLI"
fi

args=()
for m in $MODULES; do
  [ -f "$m/target/jacoco.exec" ] && args+=("$m/target/jacoco.exec")
done
[ ${#args[@]} -gt 0 ] || { echo "no jacoco.exec found: run ./mvnw verify first"; exit 2; }
classes=()
for m in $MODULES; do classes+=(--classfiles "$m/target/classes"); done

java -jar "$CLI" report "${args[@]}" "${classes[@]}" --csv target/coverage.csv >/dev/null
# CSV columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
awk -F, 'NR>1 { lm+=$8; lc+=$9; bm+=$6; bc+=$7 } END {
  printf "line coverage: %.1f%% (%d of %d lines), branch coverage: %.1f%% (%d of %d branches)\n", 100*lc/(lc+lm), lc, lc+lm, 100*bc/(bc+bm), bc, bc+bm }' target/coverage.csv
