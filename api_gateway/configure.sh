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
error()   { echo -e "${RED}[ERROR]${RESET} $*"; }

### ============================================================
###   Configuration
### ============================================================

# Setup the device route:
info "Setting route /devices with DeviceConfig plugin enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/1 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/devices",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "DeviceTranslation", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/devices"

# Setup the health route:
info "Setting route /health with ProtocolTranslation plugin enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/2 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/health",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "DeviceTranslation", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/health"


# Setup backend addition onboarding route:
info "Setting route /onboarding/backend with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/3 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/backend",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/onboarding/backend"


# Setup backend addition onboarding route:
info "Setting route /onboarding/backendAuthZ with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/4 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/backendAuthZ",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/onboarding/backendAuthZ"


# Setup backend addition onboarding route:
info "Setting route /onboarding/backendAuthZ with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/5 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/device",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/onboarding/device"


# Setup backend addition onboarding route:
info "Setting route /onboarding/deviceAuthZ with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/6 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/deviceAuthZ",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/onboarding/deviceAuthZ"