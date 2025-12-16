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
###   Start APISIX
### ============================================================

info "Building APISIX standalone image"
docker compose up -d --build

success "APISIX is running"
info "Listening on http://localhost:9080 (HTTP)"
info "Listening on http://localhost:9180 (ADMIN)"