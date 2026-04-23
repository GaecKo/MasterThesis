#!/usr/bin/env bash
# ============================================================================
# Builds and manages the backend Mosquitto broker container on nuc1.
#
# Usage:
#   ./launch_mosquitto.sh build    — build the Docker image
#   ./launch_mosquitto.sh start    — start the broker container
#   ./launch_mosquitto.sh stop     — stop and remove the container
#   ./launch_mosquitto.sh status   — show container status
#   ./launch_mosquitto.sh logs     — tail broker logs
#
# Before running 'build', place backend.crt and backend.key in this directory.
# ============================================================================

set -euo pipefail

IMAGE_NAME="edgecontrol/mosquitto-backend:latest"
CONTAINER_NAME="ec_mosquitto_backend"
MQTT_PORT="${MQTT_PORT:-8883}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { echo "[mosquitto] $*"; }
die() { echo "[mosquitto] ERROR: $*" >&2; exit 1; }

cmd_build() {
    [[ -f "$SCRIPT_DIR/backend.crt" ]] || die "backend.crt not found in $SCRIPT_DIR"
    [[ -f "$SCRIPT_DIR/backend.key" ]] || die "backend.key not found in $SCRIPT_DIR"

    log "Building image $IMAGE_NAME..."
    docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"
    log "Build complete."
}

cmd_start() {
    # Build first if the image doesn't exist yet
    if ! docker image inspect "$IMAGE_NAME" > /dev/null 2>&1; then
        log "Image not found — building first..."
        cmd_build
    fi

    # Remove any existing container
    if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        log "Removing existing container..."
        docker rm -f "$CONTAINER_NAME" > /dev/null
    fi

    log "Starting $CONTAINER_NAME on port $MQTT_PORT..."
    docker run -d \
        --name "$CONTAINER_NAME" \
        --restart unless-stopped \
        -p "${MQTT_PORT}:8883" \
        "$IMAGE_NAME"

    log "Broker started. Verify with: ./launch_mosquitto.sh logs"
}

cmd_stop() {
    if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        docker rm -f "$CONTAINER_NAME" > /dev/null
        log "Container $CONTAINER_NAME stopped and removed."
    else
        log "Container $CONTAINER_NAME is not running."
    fi
}

cmd_status() {
    docker ps --filter "name=${CONTAINER_NAME}" \
        --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" \
        | column -t
}

cmd_logs() {
    docker logs -f "$CONTAINER_NAME"
}

case "${1:-}" in
    build)  cmd_build  ;;
    start)  cmd_start  ;;
    stop)   cmd_stop   ;;
    status) cmd_status ;;
    logs)   cmd_logs   ;;
    *)
        echo "Usage: $0 {build|start|stop|status|logs}"
        echo ""
        echo "  build    Build the Docker image (requires backend.crt and backend.key)"
        echo "  start    Start the broker (builds automatically if needed)"
        echo "  stop     Stop and remove the container"
        echo "  status   Show container status"
        echo "  logs     Tail broker logs"
        echo ""
        echo "Override default port: MQTT_PORT=8883 ./launch_mosquitto.sh start"
        exit 1
        ;;
esac