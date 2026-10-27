#!/usr/bin/env bash
# Deploys the current main branch on the VM: pull, build the images here, (re)start, wait until the site answers.
# Run ON the VM, as the user that ran provision.sh:   bash ~/payledger/deploy/deploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "no .env here: run deploy/provision.sh first"; exit 2; }
HOST=$(sed -n 's/^PUBLIC_HOST=//p' .env)

git pull --ff-only
docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml up -d --build --remove-orphans

echo "== waiting for https://$HOST/"
for i in $(seq 1 90); do
  if curl -sf -o /dev/null "https://$HOST/" ; then echo "up"; docker compose ps --format 'table {{.Service}}\t{{.Status}}'; exit 0; fi
  sleep 5
done
echo "the site did not answer in 7 minutes; look at: docker compose logs caddy admin-ui"
exit 1
