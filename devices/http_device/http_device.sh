#!/usr/bin/env bash

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

### ── Sanity checks ──────────────────────────────────────────────────────────
command -v docker     >/dev/null 2>&1 || err "docker not found"
[ -f "Dockerfile" ]                   || err "Dockerfile not found at ./Dockerfile"
[ -n "${APISIX_IP:-}" ]               || err "APISIX_IP env var is not set (e.g. export APISIX_IP=192.168.2.x)"

### ── Stop and remove existing container ─────────────────────────────────────
info "Stopping any existing container..."
docker rm -f http-device-app 2>/dev/null || true

info "=== HTTP device Setup ==="
info "Device VM IP: $DEVICES_IP"

### ── Copy certs into build context ──────────────────────────────────────────


info "Building and starting http device container..."

# Build the Docker image
if [ -f "Dockerfile" ]; then
    sudo docker build -t http-device-app . 
    
    # Stop and remove existing container
    sudo docker rm -f http-device-app 2>/dev/null || true
    
    # Run with environment variables
sudo docker run -d \
    --name http-device-app \
    --network host \
    -e HTTP_DEVICE_IP=$DEVICES_IP \
    -e APISIX_IP=$APISIX_IP \
    -e INTERVAL_MS=10000 \
    -e DEVICE_ID=device_41619b34-20f4-4b84-9db3-6866c9a6b10e \
    -e API_KEY=l-TMJ98IcSQnvPQ86H1OgLnesrWFbxR2lAiFERL1Q_g \
    http-device-app
    
    success "HTTP Device container started"
    
    # Show container logs briefly
    info "Container status:"
    sudo docker ps --filter "name=http-device-app" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
else
    warn "Dockerfile not found in http_device directory"
fi



echo ""
success "=== HTTP device is running! ==="