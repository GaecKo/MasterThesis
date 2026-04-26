#!/usr/bin/env bash
set -euo pipefail

### ============================================================
###   Colors and logging
### ============================================================
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
CYAN="\033[36m"
RESET="\033[0m"

info()    { echo -e "${CYAN}[INFO]${RESET} $*"; }
success() { echo -e "${GREEN}[OK]${RESET} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET} $*"; }
err()     { echo -e "${RED}[ERROR]${RESET} $*"; exit 1; }

### ============================================================
###   Sanity checks
### ============================================================
command -v docker      >/dev/null 2>&1 || err "docker not found"
docker compose version >/dev/null 2>&1 || err "docker compose plugin not found (need Docker >= 20.10)"

[ -f "./brokers/mosquitto/mosquitto.conf" ] \
  || err "mosquitto.conf not found at ./brokers/mosquitto/mosquitto.conf"

### ============================================================
###   Start stack
### ============================================================
info "Building and starting stack (APISIX, etcd, MongoDB, Mosquitto)..."
docker compose up -d --build

success "Stack is running"
echo ""
info "APISIX   → http://localhost:9080 (HTTP)"
info "APISIX   → http://localhost:9180 (ADMIN)"
info "MongoDB  → localhost:27017"
info "Mongo UI → http://localhost:8081"
info "MQTT     → localhost:1883  (devices connect here via gateway IP)"

### ============================================================
###   Wait for Mosquitto to be ready
### ============================================================
info "Waiting for Mosquitto to be ready..."
for i in $(seq 1 15); do
  if docker compose exec -T mosquitto \
       mosquitto_pub -h localhost -t _healthcheck -m ping -q 0 2>/dev/null; then
    success "Mosquitto is up"
    break
  fi
  echo -n "."
  sleep 1
done
echo ""

### ============================================================
###   Launch configuration script
### ============================================================
info "Waiting 10 sec before configuring api-gateway..."
sleep 10

info "Launching configuration script (configure.sh)"
./configure.sh

success "Configuration script executed"
success "API Gateway launched and configured!"