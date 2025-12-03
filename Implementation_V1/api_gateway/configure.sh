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

info "=== APISIX Configuration ==="
info "Backend IP: $BACKEND_IP"
info "APISIX IP: $APISIX_IP"

# Wait a bit for APISIX to fully start
sleep 5

info "Creating APISIX route to backend..."
route_payload=$(cat <<EOF
{
  "uri": "/api/v1/*",
  "upstream": {
    "type": "roundrobin",
    "nodes": {
      "$BACKEND_IP:8000": 1
    }
  }
}
EOF
)

route_resp=$(curl -s -w 'HTTP_CODE:%{http_code}' -X PUT "http://127.0.0.1:9180/apisix/admin/routes/backend_route" -d "$route_payload")
http_code=$(echo "$route_resp" | sed -n 's/.*HTTP_CODE:\([0-9][0-9][0-9]\)$/\1/p')

if [[ "$http_code" =~ ^(200|201|204)$ ]]; then
    success "Route created (HTTP $http_code)"
    info "Route points to: $BACKEND_IP:8000"
else
    warn "Route creation returned HTTP ${http_code}"
    echo "Response: $route_resp"
fi

info "Creating key-auth consumer..."
consumer_payload=$(cat <<EOF
{
  "username": "api_client",
  "plugins": {
    "key-auth": {
      "key": "my-secret-key-123"
    }
  }
}
EOF
)

consumer_resp=$(curl -s -w 'HTTP_CODE:%{http_code}' -X PUT "http://127.0.0.1:9180/apisix/admin/consumers" -d "$consumer_payload")
http_consumer=$(echo "$consumer_resp" | sed -n 's/.*HTTP_CODE:\([0-9][0-9][0-9]\)$/\1/p')

if [[ "$http_consumer" =~ ^(200|201)$ ]]; then
    success "Consumer created (HTTP $http_consumer)"
else
    warn "Consumer creation returned HTTP ${http_consumer}"
fi

info "Enabling key-auth on route..."
patch_payload='{"plugins":{"key-auth":{}}}'
patch_resp=$(curl -s -w 'HTTP_CODE:%{http_code}' -X PATCH "http://127.0.0.1:9180/apisix/admin/routes/backend_route" -d "$patch_payload")
http_patch=$(echo "$patch_resp" | sed -n 's/.*HTTP_CODE:\([0-9][0-9][0-9]\)$/\1/p')

if [[ "$http_patch" =~ ^(200|201|204)$ ]]; then
    success "Key-auth enabled (HTTP $http_patch)"
else
    warn "Key-auth enable returned HTTP ${http_patch}"
fi

info "APISIX configuration complete!"
info "API Gateway is accessible at: http://$APISIX_IP:9080"
info "Use API key: my-secret-key-123 (header: apikey)"