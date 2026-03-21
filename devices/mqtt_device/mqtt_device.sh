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
###   Paths — derived from the script's own location
### ============================================================
BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

info "=== MQTT Device Setup ==="
info "Base dir : ${BASE_DIR}"

### ── Sanity checks ──────────────────────────────────────────────────────────
command -v docker     >/dev/null 2>&1 || err "docker not found"
[ -f "Dockerfile" ]                   || err "Dockerfile not found at ./Dockerfile"
[ -n "${APISIX_IP:-}" ]              || err "APISIX_IP env var is not set (e.g. export APISIX_IP=192.168.2.x)"

### ── Copy cert into build context ───────────────────────────────────────────
info "Copying certificate into build context..."
[ -f /usr/local/share/ca-certificates/apisix.crt ] \
  || err "Certificate not found. Run setup_device.sh first."
cp /usr/local/share/ca-certificates/apisix.crt ./apisix.crt

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
  -e DEVICE_ID=device_c69d27de-d8b0-48f6-a4b7-b2445b65ab69 \
  -e API_KEY=y7i9eiS_4BZi35beWJaNCirMmHdzhK5tE-A9aXJlq6s \
  -e BROKER_URL="mqtts://${APISIX_IP}:8883" \
  -e INTERVAL_MS=10000 \
  mqtt-device-app

### ── Status ──────────────────────────────────────────────────────────────────
echo ""
info "Container status:"
docker ps --filter "name=mqtt-device-app" \
  --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"

echo ""
success "=== MQTT device is running! ==="
echo ""
info "Useful commands:"
echo ""
echo "  # Follow device logs"
echo "  docker logs -f mqtt-device-app"
echo ""
echo "  # Stop the device"
echo "  docker rm -f mqtt-device-app"