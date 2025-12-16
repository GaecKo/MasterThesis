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
###   Checks
### ============================================================

command -v docker >/dev/null 2>&1 || {
  error "Docker is not installed"
  exit 1
}

command -v docker compose >/dev/null 2>&1 || {
  error "Docker Compose v2 is not installed"
  exit 1
}

### ============================================================
###   Start APISIX
### ============================================================

info "Building APISIX standalone image"
docker compose build || exit 1

info "Starting APISIX standalone container"
docker compose up -d || exit 1

success "APISIX standalone is running"
info "Listening on http://localhost:9080"
