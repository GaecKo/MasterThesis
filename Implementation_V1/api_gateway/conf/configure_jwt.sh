#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

### ============================================================
###   Colors and logging
### ============================================================
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
CYAN="\033[36m"
RESET="\033[0m"

info()    { echo -e "${CYAN}[> INFO]${RESET} $*"; }
success() { echo -e "${GREEN}[✓ OK]${RESET} $*"; }
warn()    { echo -e "${YELLOW}[! WARN]${RESET} $*"; }
error()   { echo -e "${RED}[x ERROR]${RESET} $*"; }

die() { error "$*"; exit 1; }

### ============================================================
### Variables
### ============================================================
APISIX_VM="apisix-vm"
BACKEND_VM="backend-vm"
BACKEND_CONSUMER="backend-service"
JWT_KEY="backend-key"
JWT_SECRET="backend-hs256-secret"
ROUTE_URI="/headers"

### ============================================================
### Get VMs IPs
### ============================================================
get_vm_ip() {
    local vm=$1
    multipass info "$vm" | awk '/IPv4:/ {print $2; exit}'
}

APISIX_IP=$(get_vm_ip "$APISIX_VM")
BACKEND_IP=$(get_vm_ip "$BACKEND_VM")

### ============================================================
### Step 1: Get admin key automatically without yq
### ============================================================
info "Retrieving APISIX admin_key from container..."

ADMIN_KEY=$(
  multipass exec "$APISIX_VM" -- bash -c '
    docker exec apisix-quickstart cat /usr/local/apisix/conf/config.yaml \
      | grep "^[[:space:]]*key:" \
      | head -n 1 \
      | sed "s/^[[:space:]]*key:[[:space:]]*//"
  '
)

echo "Admin key: $ADMIN_KEY"    

[ -z "$ADMIN_KEY" ] && die "Failed to retrieve admin_key automatically"
success "Admin key retrieved: $ADMIN_KEY"


### ============================================================
### Step 2: Create Consumer in APISIX
### ============================================================
info "Creating Consumer '$BACKEND_CONSUMER'..."
multipass exec "$APISIX_VM" -- curl -s -X PUT "http://$APISIX_IP:9180/apisix/admin/consumers/$BACKEND_CONSUMER" \
  -H "X-API-KEY: $ADMIN_KEY" \
  -d "{ \"username\": \"$BACKEND_CONSUMER\" }" >/dev/null
success "Consumer '$BACKEND_CONSUMER' created."

### ============================================================
### Step 3: Add JWT credentials for Consumer
### ============================================================
info "Adding JWT credentials for Consumer '$BACKEND_CONSUMER'..."
multipass exec "$APISIX_VM" -- curl -s -X PUT "http://$APISIX_IP:9180/apisix/admin/consumers/$BACKEND_CONSUMER/credentials" \
  -H "X-API-KEY: $ADMIN_KEY" \
  -d "{
        \"id\": \"${BACKEND_CONSUMER}-jwt\",
        \"plugins\": {
          \"jwt-auth\": {
            \"key\": \"$JWT_KEY\",
            \"secret\": \"$JWT_SECRET\"
          }
        }
      }" >/dev/null
success "JWT credentials added for '$BACKEND_CONSUMER'."

### ============================================================
### Step 4: Create a Route with jwt-auth plugin
### ============================================================
info "Creating route '$ROUTE_URI' with jwt-auth plugin..."
multipass exec "$APISIX_VM" -- curl -s -X PUT "http://$APISIX_IP:9180/apisix/admin/routes/jwt-route" \
  -H "X-API-KEY: $ADMIN_KEY" \
  -d "{
        \"uri\": \"$ROUTE_URI\",
        \"plugins\": {
          \"jwt-auth\": {}
        },
        \"upstream\": {
          \"type\": \"roundrobin\",
          \"nodes\": {
            \"httpbin.org:80\": 1
          }
        }
      }" >/dev/null
success "Route '$ROUTE_URI' created and protected with jwt-auth."


### ============================================================
### Step 5: Create a valid JWT token
### ============================================================

info "Generating JWT using variables JWT_KEY and JWT_SECRET..."

JWT=$(multipass exec "$BACKEND_VM" -- bash -c "
  export JWT_KEY='$JWT_KEY'
  export JWT_SECRET='$JWT_SECRET'
  python3 - <<EOF
import jwt
import time
import os

payload = {
    'key': os.environ['JWT_KEY'],
    'exp': int(time.time()) + 36000  # 10 hours from now
}

secret = os.environ['JWT_SECRET']
token = jwt.encode(payload, secret, algorithm='HS256')
print(token)
EOF
")


success "Generated JWT: $JWT"


### ============================================================
### Step 6: Test valid JWT request
### ============================================================

info "Testing request with valid JWT..."
multipass exec "$BACKEND_VM" -- bash -c "
  curl -i \"http://$APISIX_IP:9080$ROUTE_URI\" -H \"Authorization: $JWT\"
"

### ============================================================
### Step 6: Test invalid JWT request
### ============================================================
info "Testing request with invalid JWT..."
multipass exec "$BACKEND_VM" -- bash -c "
  curl -i \"http://$APISIX_IP:9080$ROUTE_URI\" -H \"Authorization: eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJleHAiOjE3MjY2NDk2NDAsImtleSI6ImphY2sta2V5In0.kdhumNWrZFxjU_random_random\"
"

success "JWT setup and test completed!"
