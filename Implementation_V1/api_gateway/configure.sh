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


# Setup a random route:
info "Setting route /get with ProtocolTranslation plugin enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/1  -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/get",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "ProtocolTranslation", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    },
    "upstream": {
        "type": "roundrobin",
        "nodes": {
            "httpbin.org:80": 1
        }
    }
}'
success "Route setup"
# Check 
info "Test it with: curl http://127.0.0.1:9080/get"
