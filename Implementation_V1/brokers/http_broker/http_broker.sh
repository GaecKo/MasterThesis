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

info "=== HTTP Broker Setup ==="
info "Broker VM IP: $BROKERS_IP"
info "APISIX Gateway IP: $APISIX_IP"

info "Setting up environment variables..."

# Create .env file with APISIX_IP for Node.js app
cat > /home/ubuntu/brokers/http_broker/.env <<EOF
# API Gateway configuration
APISIX_GATEWAY_URL=http://$APISIX_IP:9080
APISIX_GATEWAY_IP=$APISIX_IP
BROKERS_IP=$BROKERS_IP
BACKEND_PORT=8000

# Application settings
NODE_ENV=production
PORT=8000
EOF

success "Created .env file with gateway configuration and env variable"

info "Building and starting backend container..."
cd /home/ubuntu/brokers/http_broker/

# Build the Docker image
if [ -f "Dockerfile" ]; then
    sudo docker build -t http-broker-app . 
    
    # Stop and remove existing container
    sudo docker rm -f http-broker-app 2>/dev/null || true
    
    # Run with environment variables
    sudo docker run -d \
        --name http-broker-app \
        --network host \
        --env-file .env \
        http-broker-app
    
    success "HTTP Broker container started"
    
    # Show container logs briefly
    info "Container status:"
    sudo docker ps --filter "name=http-broker-app" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
else
    warn "Dockerfile not found in http_broker directory"
fi

info "HTTP Broker setup complete!"
info "HTTP Broker API is accessible at: http://$BROKER_IP:8000"
info "Will be proxied through APISIX at: http://$APISIX_IP:9080/api/v1/*"