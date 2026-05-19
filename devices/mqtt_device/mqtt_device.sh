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

### ── Sanity checks ──────────────────────────────────────────────────────────
command -v docker     >/dev/null 2>&1 || err "docker not found"
[ -f "Dockerfile" ]                   || err "Dockerfile not found at ./Dockerfile"
[ -n "${APISIX_IP:-}" ]               || err "APISIX_IP env var is not set (e.g. export APISIX_IP=192.168.2.x)"

info "=== MQTT Device Setup ==="
info "Device VM IP: $DEVICES_IP"


### ── Build image ─────────────────────────────────────────────────────────────
info "Building mqtt-device-app image..."
docker build -t mqtt-device-app ./

### ── Stop and remove existing container ─────────────────────────────────────
info "Stopping any existing container..."
docker rm -f mqtt-device-app 2>/dev/null || true

### ── Run ─────────────────────────────────────────────────────────────────────
info "Starting mqtt-device-app..."
docker run -d \
  --name mqtt-device-app \
  --network host \
  --restart unless-stopped \
  -e DEVICE_ID=device_a3922169-f24a-40c5-b57a-9a45f7a3541d \
  -e API_KEY=DTPc-v4Jtku6_qRDbemD_PQ-hfCkrK3Z3UyH6ybkRyg \
  -e BROKER_URL="mqtt://$APISIX_IP:1883" \
  -e INTERVAL_MS=10000 \
  mqtt-device-app

### ── Status ──────────────────────────────────────────────────────────────────
echo ""
info "Container status:"
docker ps --filter "name=mqtt-device-app" \
  --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"

echo ""
success "=== MQTT device is running! ==="
