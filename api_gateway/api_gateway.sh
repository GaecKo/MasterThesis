#!/usr/bin/env bash
set -euo pipefail

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
err()     { echo -e "${RED}[ERROR]${RESET} $*"; exit 1; }

### ============================================================
###   Sanity checks
### ============================================================
command -v docker      >/dev/null 2>&1 || err "docker not found"
docker compose version >/dev/null 2>&1 || err "docker compose plugin not found (need Docker >= 20.10)"

[ -f "./brokers/mosquitto/mosquitto.conf" ] \
  || err "mosquitto.conf not found at ./brokers/mosquitto/mosquitto.conf"

### ============================================================
###   Token encryption key setup
### ============================================================
TOKEN_KEY_FILE="./token_encryption_key.txt"

info "Checking for token encryption key file..."

if [ ! -f "$TOKEN_KEY_FILE" ]; then
    warn "Token encryption key file not found, generating new key..."
    
    # Generate a random base64 key and save to file
    openssl rand -base64 32 > "$TOKEN_KEY_FILE"
    
    # Check if key generation was successful
    if [ $? -eq 0 ] && [ -s "$TOKEN_KEY_FILE" ]; then
        success "Token encryption key generated and saved to $TOKEN_KEY_FILE"
    else
        err "Failed to generate token encryption key"
    fi
else
    success "Token encryption key file found at $TOKEN_KEY_FILE"
fi

### ============================================================
###   Java plugin JAR check and rebuild
### ============================================================
JAR_PATH="./java-plugins/edge-control/target/edge-control-0.0.1-SNAPSHOT.jar"
REFRESH_SCRIPT="./java-plugins/refresh_jar.sh"

info "Checking for Java plugin JAR file..."

if [ ! -f "$JAR_PATH" ]; then
    warn "JAR file not found at $JAR_PATH, will build it now..."

    # Make sure the script is executable
    chmod +x "$REFRESH_SCRIPT" 2>/dev/null || warn "Could not chmod $REFRESH_SCRIPT"
    
    # Change to java-plugins directory and run the script
    (cd ./java-plugins && ./refresh_jar.sh)
    
    # Check if the script executed successfully
    if [ $? -eq 0 ]; then
        success "Successfully executed refresh_jar.sh"
    else
        err "refresh_jar.sh execution failed. Please check the script manually."
    fi
    
    # Verify JAR was created
    if [ ! -f "$JAR_PATH" ]; then
        err "JAR file still not found after running refresh_jar.sh. Expected at $JAR_PATH"
    else
        success "JAR file verified at $JAR_PATH"
    fi
    
else
    success "JAR file found at $JAR_PATH"
fi

### ============================================================
###   Start stack
### ============================================================
info "Building and starting stack (APISIX, etcd, MongoDB, Mosquitto)..."
docker compose up -d --build

success "Stack is running"
echo ""
info "APISIX   → http://localhost:9080 (HTTP)"
info "APISIX   → http://localhost:9180 (ADMIN)"
info "MongoDB  → localhost:27017"
info "Mongo UI → http://localhost:8081"
info "MQTT     → localhost:1883  (devices connect here via gateway IP)"

### ============================================================
###   Wait for Mosquitto to be ready
### ============================================================
info "Waiting for Mosquitto to be ready..."
for i in $(seq 1 15); do
  if docker compose exec -T mosquitto \
       mosquitto_pub -h localhost -t _healthcheck -m ping -q 0 2>/dev/null; then
    success "Mosquitto is up"
    break
  fi
  echo -n "."
  sleep 1
done
echo ""

### ============================================================
###   Wait for APISIX to be fully ready (including etcd connection)
### ============================================================
info "Waiting for APISIX to be ready..."

max_retries=30
retry_count=0

while [ $retry_count -lt $max_retries ]; do
    # Try a harmless GET request to the admin API
    if curl -s -f -H 'X-API-KEY: admin' "http://localhost:9180/apisix/admin/routes" > /dev/null 2>&1; then
        success "APISIX is ready"
        break
    fi
    
    retry_count=$((retry_count + 1))
    if [ $retry_count -eq $max_retries ]; then
        err "APISIX failed to become ready after ${max_retries} attempts"
    fi
    
    sleep 2
done

### ============================================================
###   Launch configuration script
### ============================================================
info "Launching configuration script (configure.sh)"
./configure.sh

success "Configuration script executed"
success "API Gateway launched and configured!"