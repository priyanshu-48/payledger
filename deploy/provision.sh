#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 VM (Oracle Cloud Always Free Arm, or any other): Docker, swap, the code, and a
# random .env that lives only on this machine. Run it ON the VM as the normal login user (ubuntu), once:
#
#   bash provision.sh <public-ip>          e.g. bash provision.sh 203.0.113.7
#
# Safe to run again: it skips what is already done and never overwrites an existing .env.
set -euo pipefail

IP=${1:?usage: provision.sh <public-ip>}
REPO=${REPO:-https://github.com/priyanshu-48/payledger.git}
DIR=${DIR:-$HOME/payledger}
HOST="${IP//./-}.sslip.io"

echo "== packages and Docker"
sudo apt-get update -y
sudo apt-get install -y ca-certificates curl git openssl python3
if ! command -v docker >/dev/null; then
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
sudo usermod -aG docker "$USER"

echo "== 4 GB swap (the first Maven build in Docker is memory hungry)"
if [ ! -f /swapfile ]; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "== code"
[ -d "$DIR/.git" ] || git clone "$REPO" "$DIR"

echo "== .env (random secrets, stays on this VM)"
cd "$DIR"
if [ ! -f .env ]; then
  bash ops/ci/make-env.sh
  printf 'PUBLIC_HOST=%s\n' "$HOST" >> .env
  chmod 600 .env
fi

echo
echo "Done. Public address will be https://$HOST/"
echo "Log out and in again (so the docker group applies), then run: bash $DIR/deploy/deploy.sh"
