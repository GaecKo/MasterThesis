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

info "=== HTTP device Setup ==="
info "Device VM IP: $DEVICES_IP"

info "Setting up environment variables..."

# Create .env file with APISIX_IP for Node.js app
cat > /home/ubuntu/devices/http_device/.env <<EOF
# API Gateway configuration
HTTP_DEVICE_IP=$DEVICES_IP
DEVICE_ID=1


EOF

success "Created .env file with env variable"

info "Building and starting http device container..."
cd /home/ubuntu/devices/http_device/

# Build the Docker image
if [ -f "Dockerfile" ]; then
    sudo docker build -t http-device-app . 
    
    # Stop and remove existing container
    sudo docker rm -f http-device-app 2>/dev/null || true
    
    # Run with environment variables
    sudo docker run -d \
        --name http-device-app \
        --network host \
        --env-file .env \
        http-device-app
    
    success "HTTP Device container started"
    
    # Show container logs briefly
    info "Container status:"
    sudo docker ps --filter "name=http-device-app" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
else
    warn "Dockerfile not found in http_device directory"
fi

info "HTTP Device setup complete!"
info "HTTP Device API is accessible at: http://$DEVICES_IP:8000"