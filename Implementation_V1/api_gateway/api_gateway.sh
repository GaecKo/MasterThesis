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

info "=== APISIX Setup ==="
info "APISIX VM IP: ${APISIX_IP:-NOT_SET}"
info "Backend VM IP: ${BACKEND_IP:-NOT_SET}"

info "Installing APISIX (quiet mode)..."
curl -sL https://run.api7.ai/apisix/quickstart 2>/dev/null | sh >/tmp/apisix-install.log 2>&1 || {
    warn "APISIX installation may have warnings - check /tmp/apisix-install.log"
}


info "APISIX setup complete!"