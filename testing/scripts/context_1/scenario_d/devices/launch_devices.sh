#!/usr/bin/env bash
# ============================================================================
# Builds and manages HTTP and MQTT device simulator containers on nuc8.
#
# Reads device IDs and HTTP port assignments from generated_configs/_summary.json.
# Each device container gets a unique HTTPS_PORT env var so the JS process
# binds to that specific port — the same port the translation config references.
#
# Usage:
#   ./launch_devices.sh build    — build both Docker images
#   ./launch_devices.sh start    — start all device containers
#   ./launch_devices.sh stop     — stop and remove all device containers
#   ./launch_devices.sh status   — show running device containers
#   ./launch_devices.sh logs <device_id>
#
# Before running 'build', place the following in this directory:
#   device.crt   device.key   backend.crt
# ============================================================================

set -euo pipefail

# | ================= Configuration ================= |

HTTP_IMAGE="${HTTP_IMAGE:-edgecontrol/http-device:latest}"
MQTT_IMAGE="${MQTT_IMAGE:-edgecontrol/mqtt-device:latest}"

# Gateway NUC — HTTP devices send telemetry here
APISIX_IP="${APISIX_IP:-nuc4-pc.local}"

# Device NUC — used for health check logging inside the container
HTTP_DEVICE_IP="${HTTP_DEVICE_IP:-nuc8-pc.local}"

# Backend NUC — MQTT devices connect to Mosquitto here
MQTT_BROKER_URL="${MQTT_BROKER_URL:-mqtts://nuc1-pc.local:8883}"

# Keep telemetry quiet during perf tests to avoid noise in measurements
TELEMETRY_INTERVAL_MS="${TELEMETRY_INTERVAL_MS:-60000}"

SUMMARY_PATH="${SUMMARY_PATH:-./generated_configs/_summary.json}"
CONTAINER_PREFIX="ec_device"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# | ================= Helpers ================= |

log() { echo "[launch_devices] $*"; }
die() { echo "[launch_devices] ERROR: $*" >&2; exit 1; }

require_summary() {
    [[ -f "$SUMMARY_PATH" ]] || die "Summary not found at $SUMMARY_PATH — run generate_configs.py first"
}

require_certs() {
    [[ -f "$SCRIPT_DIR/device.crt"  ]] || die "device.crt not found in $SCRIPT_DIR"
    [[ -f "$SCRIPT_DIR/device.key"  ]] || die "device.key not found in $SCRIPT_DIR"
    [[ -f "$SCRIPT_DIR/apisix.crt" ]] || die "apisix.crt not found in $SCRIPT_DIR"
}

remove_if_exists() {
    local name="$1"
    if docker ps -a --format '{{.Names}}' | grep -q "^${name}$"; then
        docker rm -f "$name" > /dev/null
    fi
}

# | ================= Commands ================= |

cmd_build() {
    require_certs
    log "Building HTTP device image: $HTTP_IMAGE"
    docker build -f "$SCRIPT_DIR/Dockerfile.http" -t "$HTTP_IMAGE" "$SCRIPT_DIR"
    log "Building MQTT device image: $MQTT_IMAGE"
    docker build -f "$SCRIPT_DIR/Dockerfile.mqtt" -t "$MQTT_IMAGE" "$SCRIPT_DIR"
    log "Build complete."
}

cmd_start() {
    require_summary

    # Auto-build if images don't exist yet
    if ! docker image inspect "$HTTP_IMAGE" > /dev/null 2>&1 \
    || ! docker image inspect "$MQTT_IMAGE" > /dev/null 2>&1; then
        log "One or more images not found — building first..."
        cmd_build
    fi

    # Extract API key for the first backend (used for telemetry forwarding)
    API_KEY=$(python3 -c "
import json
data = json.load(open('$SUMMARY_PATH'))
keys = list(data['backends'].values())
print(keys[0] if keys else '')
")

    # ── HTTP devices ──────────────────────────────────────────────────────────
    HTTP_DEVICES=$(python3 -c "
import json
data = json.load(open('$SUMMARY_PATH'))
for dev_id, port in data.get('http_device_ports', {}).items():
    print(dev_id, port)
")

    if [[ -n "$HTTP_DEVICES" ]]; then
        log "Starting HTTP device containers..."
        while IFS=' ' read -r device_id https_port; do
            container_name="${CONTAINER_PREFIX}_${device_id}"
            remove_if_exists "$container_name"

            log "  $container_name — HTTPS_PORT=$https_port"
            docker run -d \
                --name "$container_name" \
                --restart unless-stopped \
                -p "${https_port}:${https_port}" \
                -e "DEVICE_ID=${device_id}" \
                -e "HTTPS_PORT=${https_port}" \
                -e "APISIX_IP=${APISIX_IP}" \
                -e "HTTP_DEVICE_IP=${HTTP_DEVICE_IP}" \
                -e "INTERVAL_MS=${TELEMETRY_INTERVAL_MS}" \
                -e "API_KEY=${API_KEY}" \
                "$HTTP_IMAGE" > /dev/null

            log "    ✓ $container_name (port $https_port)"
        done <<< "$HTTP_DEVICES"
    else
        log "No HTTP devices found in summary."
    fi

    # ── MQTT devices ──────────────────────────────────────────────────────────
    MQTT_DEVICES=$(python3 -c "
import json
data = json.load(open('$SUMMARY_PATH'))
for dev_id, info in data['devices'].items():
    if info['adapter'] == 'mqtt':
        print(dev_id)
")

    if [[ -n "$MQTT_DEVICES" ]]; then
        log "Starting MQTT device containers..."
        while IFS= read -r device_id; do
            container_name="${CONTAINER_PREFIX}_${device_id}"
            remove_if_exists "$container_name"

            log "  $container_name — broker: $MQTT_BROKER_URL"
            docker run -d \
                --name "$container_name" \
                --restart unless-stopped \
                -e "DEVICE_ID=${device_id}" \
                -e "BROKER_URL=${MQTT_BROKER_URL}" \
                -e "INTERVAL_MS=${TELEMETRY_INTERVAL_MS}" \
                -e "API_KEY=${API_KEY}" \
                -e "CA_CERT_PATH=/certs/apisix.crt" \
                "$MQTT_IMAGE" > /dev/null

            log "    ✓ $container_name"
        done <<< "$MQTT_DEVICES"
    else
        log "No MQTT devices found in summary."
    fi

    echo ""
    log "All containers started. Run './launch_devices.sh status' to verify."
}

cmd_stop() {
    log "Stopping all device containers (prefix: $CONTAINER_PREFIX)..."
    containers=$(docker ps -a --format '{{.Names}}' | grep "^${CONTAINER_PREFIX}_" || true)
    if [[ -z "$containers" ]]; then
        log "No device containers found."
        return
    fi
    while IFS= read -r name; do
        docker rm -f "$name" > /dev/null
        log "  Removed $name"
    done <<< "$containers"
    log "Done."
}

cmd_status() {
    docker ps --filter "name=${CONTAINER_PREFIX}_" \
        --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" \
        | column -t
}

cmd_logs() {
    local device_id="${1:-}"
    [[ -n "$device_id" ]] || die "Usage: $0 logs <device_id>"
    docker logs -f "${CONTAINER_PREFIX}_${device_id}"
}

# | ================= Entrypoint ================= |

case "${1:-}" in
    build)  cmd_build ;;
    start)  cmd_start ;;
    stop)   cmd_stop  ;;
    status) cmd_status ;;
    logs)   cmd_logs "${2:-}" ;;
    *)
        echo "Usage: $0 {build|start|stop|status|logs <device_id>}"
        echo ""
        echo "  build               Build HTTP and MQTT device images"
        echo "  start               Start all device containers from _summary.json"
        echo "  stop                Stop and remove all device containers"
        echo "  status              Show running device containers"
        echo "  logs <device_id>    Tail logs for a specific device"
        echo ""
        echo "Env var overrides:"
        echo "  HTTP_IMAGE              (default: edgecontrol/http-device:latest)"
        echo "  MQTT_IMAGE              (default: edgecontrol/mqtt-device:latest)"
        echo "  APISIX_IP               (default: nuc4-pc.local)"
        echo "  MQTT_BROKER_URL         (default: mqtts://nuc1-pc.local:8883)"
        echo "  TELEMETRY_INTERVAL_MS   (default: 60000)"
        echo "  SUMMARY_PATH            (default: ./generated_configs/_summary.json)"
        exit 1
        ;;
esac