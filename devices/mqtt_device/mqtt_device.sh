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
###   Paths — derived from the script's own location so it works
###   regardless of where it is called from
### ============================================================
BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
MQTT_DIR="${BASE_DIR}/mqtt_device"
MOSQUITTO_DIR="${BASE_DIR}/mosquitto"
COMPOSE_FILE="${BASE_DIR}/docker-compose.yml"

info "=== MQTT Stack Setup ==="
info "Base dir : ${BASE_DIR}"

### ── Sanity checks ──────────────────────────────────────────────────────────
command -v docker        >/dev/null 2>&1 || err "docker not found"
docker compose version   >/dev/null 2>&1 || err "docker compose plugin not found (need Docker >= 20.10)"
[ -f "${COMPOSE_FILE}" ]                 || err "docker-compose.yml not found at ${COMPOSE_FILE}"
[ -f "${MQTT_DIR}/Dockerfile" ]          || err "Dockerfile not found at ${MQTT_DIR}/Dockerfile"
[ -f "${MOSQUITTO_DIR}/mosquitto.conf" ] || err "mosquitto.conf not found at ${MOSQUITTO_DIR}/mosquitto.conf"

### ── Write .env for the device container ────────────────────────────────────
info "Writing ${MQTT_DIR}/.env ..."
cat > "${MQTT_DIR}/.env" <<EOF
DEVICE_ID=device_3714cba3-8be4-4097-b862-b06c73e75d5b
BROKER_URL=mqtt://127.0.0.1:1883
INTERVAL_MS=30000
EOF
success ".env written"

### ── Tear down any previous stack ───────────────────────────────────────────
info "Stopping any existing stack..."
docker compose -f "${COMPOSE_FILE}" down --remove-orphans 2>/dev/null || true

### ── Build & start ───────────────────────────────────────────────────────────
info "Building images..."
docker compose -f "${COMPOSE_FILE}" build

info "Starting stack (broker + device)..."
docker compose -f "${COMPOSE_FILE}" up -d

### ── Wait for broker to be ready ─────────────────────────────────────────────
info "Waiting for Mosquitto to be ready..."
for i in $(seq 1 15); do
  if docker compose -f "${COMPOSE_FILE}" exec -T mosquitto \
       mosquitto_pub -h localhost -t _healthcheck -m ping -q 0 2>/dev/null; then
    success "Mosquitto is up"
    break
  fi
  echo -n "."
  sleep 1
done

### ── Status ──────────────────────────────────────────────────────────────────
echo ""
info "Stack status:"
docker compose -f "${COMPOSE_FILE}" ps

echo ""
success "=== MQTT stack is running! ==="
echo ""
info "Useful commands:"
echo ""
echo "  # Follow all logs"
echo "  docker compose -f ${COMPOSE_FILE} logs -f"
echo ""
echo "  # Device logs only"
echo "  docker compose -f ${COMPOSE_FILE} logs -f mqtt-device"
echo ""
echo "  # Watch all MQTT traffic (runs a temporary subscriber)"
echo "  docker run --rm --network host eclipse-mosquitto:2 mosquitto_sub -h localhost -t 'devices/#' -v"
echo ""
echo "  # Send a ping command to the device"
echo "  docker run --rm --network host eclipse-mosquitto:2 \\"
echo "    mosquitto_pub -h localhost -t 'devices/2/commands' \\"
echo "    -m '{\"deviceId\":\"2\",\"timestamp\":\"\",\"type\":\"command\",\"payload\":{\"action\":\"ping\",\"params\":{}}}'"
echo ""
echo "  # Tear everything down"
echo "  docker compose -f ${COMPOSE_FILE} down"