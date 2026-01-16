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
        warn "VM '$name' already exists — starting it."
        multipass start $name 
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
create_vm "devices-vm"

info "=== STEP 2: Mounting folders to VMs ==="

# Mount api_gateway folder to apisix-vm
mount_folder "./api_gateway" "apisix-vm" "/home/ubuntu/api_gateway"

# Mount backend folder to backend-vm
mount_folder "./backend" "backend-vm" "/home/ubuntu/backend"

# Mount device folder to device-vm
mount_folder "./devices" "devices-vm" "/home/ubuntu/devices/"

info "=== STEP 3: Getting VM IPs for configuration ==="
APISIX_IP=$(get_vm_ip "apisix-vm")
BACKEND_IP=$(get_vm_ip "backend-vm")
DEVICES_IP=$(get_vm_ip "devices-vm")

[ -z "$APISIX_IP" ] && die "Could not get APISIX VM IP"
[ -z "$BACKEND_IP" ] && die "Could not get Backend VM IP"
[ -z "$DEVICES_IP" ] && die "Could not get Devices VM IP"

info "APISIX VM IP: $APISIX_IP"
info "Backend VM IP: $BACKEND_IP"
info "Devices VM IP: $DEVICES_IP"

info "=== STEP 4: Setting up APISIX ==="
info "Installing OpenJDK 17 on APISIX VM..."
multipass exec apisix-vm -- bash -c "
    yes | sudo apt install openjdk-17-jdk >/dev/null 2>&1
"
success "OpenJDK 17 installed on APISIX VM"
if [ -d "./api_gateway" ] && [ -f "./api_gateway/api_gateway.sh" ]; then
    # Read the script content and execute it directly with bash
    APISIX_SETUP_CONTENT=$(cat "./api_gateway/api_gateway.sh")
    
    multipass exec apisix-vm -- bash -c "
        export APISIX_IP='$APISIX_IP'
        export BACKEND_IP='$BACKEND_IP'
        cd /home/ubuntu/api_gateway
        $APISIX_SETUP_CONTENT
    "
else
    warn "api_gateway/api_gateway.sh not found"
fi

info "=== STEP 5: Setting up Backend ==="
if [ -d "./backend" ] && [ -f "./backend/backend.sh" ]; then
    # Read the script content and execute it directly with bash
    BACKEND_SETUP_CONTENT=$(cat "./backend/backend.sh")
    
    multipass exec backend-vm -- bash -c "
        echo '=== Starting Backend setup ==='
        export APISIX_IP='$APISIX_IP'
        export BACKEND_IP='$BACKEND_IP'
        cd /home/ubuntu/backend
        $BACKEND_SETUP_CONTENT
    "
else
    warn "backend/backend.sh not found"
fi

info "=== STEP 7: Setting up http-device ==="
if [ -d "./devices/http_device" ] && [ -f "./devices/http_device/http_device.sh" ]; then
    # Read the script content and execute it directly with bash
    HTTP_DEVICE_SETUP_CONTENT=$(cat "./devices/http_device/http_device.sh")
    
    multipass exec devices-vm -- bash -c "
        echo '=== Starting HTTP Device setup ==='
        export DEVICES_IP='$DEVICES_IP'
        cd /home/ubuntu/devices/http_device
        $HTTP_DEVICE_SETUP_CONTENTh
    "
else
    warn "devices/http_device/http_device.sh not found"
fi

success "Setup complete!"
echo -e "${YELLOW}Summary:${RESET}"
echo "  APISIX Gateway IP: $APISIX_IP"
echo "  Backend IP: $BACKEND_IP"
echo "  Devices IP: $DEVICES_IP"
echo ""
echo -e "${YELLOW}Access URLs:${RESET}"
echo "  Backend direct:      http://$BACKEND_IP:8000"
echo "  HTTP Device direct:  http://$DEVICES_IP:8000"
echo "  APISIX interface:    http://$APISIX_IP:9180/ui"
echo ""
echo -e "${YELLOW}Test the Backend health:${RESET}"
echo "  curl http://$BACKEND_IP:8000/health"
echo ""
echo -e "${YELLOW}Test the HTTP Device health:${RESET}"
echo "  curl http://$DEVICES_IP:8000/health"
echo ""
echo -e "${YELLOW}VM Management:${RESET}"
echo "  multipass shell apisix-vm    # Enter APISIX VM"
echo "  multipass shell backend-vm   # Enter Backend VM"
echo "  multipass shell device-vm    # Enter Device VM"
echo "  multipass exec backend-vm -- docker logs -f backend-app         # see logs of backend app"
echo "  multipass exec device-vm -- docker logs -f http-device-app     # see logs of  http-device-app"
echo "  multipass exec apisix-vm -- docker logs -f apisix               # see logs of apisix app"