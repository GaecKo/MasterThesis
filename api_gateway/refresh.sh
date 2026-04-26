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
###   1. rebuild jar
### ============================================================

info "Stoping and removing the containers..."
docker stop apisix apisix-etcd mongodb mongo-express mosquitto
docker rm apisix apisix-etcd mongodb mongo-express mosquitto
success "Stopped the containers successfully" 

info "Rebuilding the jar..."
cd java-plugins/
./refresh_jar.sh 
success ".jar rebuilt"

info "Relaunching docker containers"
cd ../ 
./api_gateway.sh 
success "APISIX and ETCD containers relaunched"

success "API Gateway refreshed!"
