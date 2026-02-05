#!/usr/bin/env bash

echo "Cleaning up APISIX/Backend VMs..."

# Stop and delete specific VMs
for vm in apisix-vm backend-vm devices-vm; do
    if multipass list | grep -q "$vm"; then
        echo "Stopping $vm..."
        multipass stop "$vm" 2>/dev/null || true
        echo "Deleting $vm..."
        multipass delete --purge "$vm" 2>/dev/null || true
    fi
done

echo "✅ Cleanup complete!"
echo ""
echo "Remaining VMs:"
multipass list