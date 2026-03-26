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
info "Copying certificates into build context..."
[ -f /usr/local/share/ca-certificates/apisix.crt ] \
  || err "apisix.crt not found. Run setup_device_TLS.sh first."
[ -f ~/certs/server.crt ] \
  || err "device.crt not found. Run setup_device_TLS.sh first."
cp /usr/local/share/ca-certificates/apisix.crt ./apisix.crt
cp ~/certs/server.crt ./device.crt
cp ~/certs/server.key ./device.key

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
        -e INTERVAL_MS=5000 \
        -e DEVICE_ID=device_382109bd-7428-4cb9-b075-9a0ef2041560 \
        http-device-app
    
    success "HTTP Device container started"
    
    # Show container logs briefly
    info "Container status:"
    sudo docker ps --filter "name=http-device-app" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
else
    warn "Dockerfile not found in http_device directory"
fi

info "HTTP Device setup complete!"
info "HTTP Device API is accessible at: https://$DEVICES_IP:8000"