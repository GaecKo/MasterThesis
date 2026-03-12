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

info "Setting up environment variables..."

# Create .env file with APISIX_IP for Node.js app
cat > /home/ubuntu/backend/.env <<EOF
# API Gateway configuration
APISIX_GATEWAY_URL=http://$APISIX_IP:9080
APISIX_GATEWAY_IP=$APISIX_IP
BACKEND_IP=$BACKEND_IP
BACKEND_PORT=8000

# Application settings
NODE_ENV=production
PORT=8000
EOF

success "Created .env file with gateway configuration and env variable"

info "Building and starting backend container..."
cd /home/ubuntu/backend

# Build the Docker image
if [ -f "Dockerfile" ]; then
    sudo docker build -t backend-app . 
    
    # Stop and remove existing container
    sudo docker rm -f backend-app 2>/dev/null || true
    
    # Run with environment variables
    sudo docker run -d \
        --name backend-app \
        --network host \
        --env-file .env \
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