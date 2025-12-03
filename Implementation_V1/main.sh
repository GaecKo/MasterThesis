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
###   Helper functions
### ============================================================
create_vm() {
    local name=$1

    if multipass list --format csv | awk -F, 'NR>1{print $1}' | grep -xq "$name"; then
        warn "VM '$name' already exists — skipping creation."
    else
        info "Creating VM '$name'..."
        multipass launch --name "$name" --memory 2G --disk 10G --cloud-init utils/cloud-init.yaml
        success "VM '$name' created."
    fi
}

get_vm_ip() {
    local vm=$1
    multipass info "$vm" | awk '/IPv4:/ {print $2; exit}'
}

mount_folder() {
    local local_path=$1
    local vm=$2
    local vm_path=$3
    
    if [ ! -d "$local_path" ]; then
        warn "Local folder '$local_path' not found - skipping mount"
        return 1
    fi
    
    # Check if already mounted
    if multipass info "$vm" | grep -q "$vm_path"; then
        info "Folder already mounted to $vm:$vm_path"
        return 0
    fi
    
    info "Mounting $local_path to $vm:$vm_path"
    multipass mount "$local_path" "${vm}:${vm_path}"
    success "Mounted $local_path to $vm"
}

### ============================================================
###   Main script
### ============================================================

info "=== STEP 1: Creating VMs (using cloud-init) ==="
create_vm "apisix-vm"
create_vm "backend-vm"

info "=== STEP 2: Mounting folders to VMs ==="

# Mount api_gateway folder to apisix-vm
mount_folder "./api_gateway" "apisix-vm" "/home/ubuntu/api_gateway"

# Mount backend folder to backend-vm
mount_folder "./backend" "backend-vm" "/home/ubuntu/backend"

info "=== STEP 3: Getting VM IPs for configuration ==="
APISIX_IP=$(get_vm_ip "apisix-vm")
BACKEND_IP=$(get_vm_ip "backend-vm")

[ -z "$APISIX_IP" ] && die "Could not get APISIX VM IP"
[ -z "$BACKEND_IP" ] && die "Could not get Backend VM IP"

info "APISIX VM IP: $APISIX_IP"
info "Backend VM IP: $BACKEND_IP"

info "=== STEP 4: Setting up APISIX ==="
if [ -d "./api_gateway" ] && [ -f "./api_gateway/setup.sh" ]; then
    # Read the script content and execute it directly with bash
    APISIX_SETUP_CONTENT=$(cat "./api_gateway/setup.sh")
    
    multipass exec apisix-vm -- bash -c "
        echo '=== Starting APISIX setup ==='
        export APISIX_IP='$APISIX_IP'
        export BACKEND_IP='$BACKEND_IP'
        cd /home/ubuntu/api_gateway
        $APISIX_SETUP_CONTENT
    "
else
    warn "api_gateway/setup.sh not found"
fi

info "=== STEP 5: Setting up Backend ==="
if [ -d "./backend" ] && [ -f "./backend/setup.sh" ]; then
    # Read the script content and execute it directly with bash
    BACKEND_SETUP_CONTENT=$(cat "./backend/setup.sh")
    
    multipass exec backend-vm -- bash -c "
        echo '=== Starting Backend setup ==='
        export APISIX_IP='$APISIX_IP'
        export BACKEND_IP='$BACKEND_IP'
        cd /home/ubuntu/backend
        $BACKEND_SETUP_CONTENT
    "
else
    warn "backend/setup.sh not found"
fi

# info "=== STEP 6: Configuring APISIX Route ==="
# if [ -d "./api_gateway" ] && [ -f "./api_gateway/configure.sh" ]; then
#     # Read the script content and execute it directly with bash
#     APISIX_CONFIGURE_CONTENT=$(cat "./api_gateway/configure.sh")
    
#     multipass exec apisix-vm -- bash -c "
#         echo '=== Configuring APISIX route ==='
#         export APISIX_IP='$APISIX_IP'
#         export BACKEND_IP='$BACKEND_IP'
#         cd /home/ubuntu/api_gateway
#         $APISIX_CONFIGURE_CONTENT
#     "
# else
#     warn "api_gateway/configure.sh not found"
# fi

# info "=== STEP 7: Verification ==="
# info "Testing APISIX gateway..."
# Give services time to start
# sleep 5

# Test backend directly
# if multipass exec backend-vm -- curl -s "http://localhost:8000/health" >/dev/null 2>&1; then
#     success "Backend is running on backend-vm:8000"
# else
#     warn "Backend health check failed"
# fi

# Test APISIX admin API
# if multipass exec apisix-vm -- curl -s "http://127.0.0.1:9180/" >/dev/null 2>&1; then
#     success "APISIX admin API is accessible"
# else
#     warn "APISIX admin API not responding"
# fi

success "Setup complete!"
echo -e "${YELLOW}Summary:${RESET}"
echo "  APISIX Gateway IP: $APISIX_IP"
echo "  Backend API IP: $BACKEND_IP"
echo ""
echo -e "${YELLOW}Access URLs:${RESET}"
echo "  Backend direct:      http://$BACKEND_IP:8000"
echo ""
echo -e "${YELLOW}Test the Backend health:${RESET}"
echo "  curl http://$BACKEND_IP:8000/health"
echo ""
echo -e "${YELLOW}VM Management:${RESET}"
echo "  multipass shell apisix-vm    # Enter APISIX VM"
echo "  multipass shell backend-vm   # Enter Backend VM"
