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

info "=== Backend Setup ==="
info "Backend VM IP: $BACKEND_IP"
info "APISIX Gateway IP: $APISIX_IP"


info "Building and starting backend container..."
# cd /home/ubuntu/backend

# Build the Docker image
if [ -f "Dockerfile" ]; then
    sudo docker build -t backend-app . 
    
    # Stop and remove existing container
    sudo docker rm -f backend-app 2>/dev/null || true

    ### ── Copy certs into build context ──────────────────────────────────────────
    info "Copying certificates into build context..."
    [ -f /usr/local/share/ca-certificates/apisix.crt ] \
    || err "apisix.crt not found. Run setup_backend_TLS.sh first."
    [ -f ~/certs/server.crt ] \
    || err "backend.crt not found. Run setup_backend_TLS.sh first."
    cp /usr/local/share/ca-certificates/apisix.crt ./apisix.crt
    cp ~/certs/server.crt ./backend.crt
    cp ~/certs/server.key ./backend.key
    
    # Run with environment variables
    sudo docker run -d \
        --name backend-app \
        --network host \
        backend-app
    
    success "Backend container started"
    
    # Show container logs briefly
    info "Container status:"
    sudo docker ps --filter "name=backend-app" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
else
    warn "Dockerfile not found in backend directory"
fi

info "Backend setup complete!"
info "Backend API is accessible at: http://$BACKEND_IP:8000"
info "Will be proxied through APISIX at: http://$APISIX_IP:9080/api/v1/*"