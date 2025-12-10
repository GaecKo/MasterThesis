#!/usr/bin/env bash

echo "Stopping up APISIX/Backend VMs..."

# Stop and delete specific VMs
for vm in apisix-vm backend-vm device1-vm; do
    if multipass list | grep -q "$vm"; then
        echo "Stopping $vm..."
        multipass stop "$vm" 2>/dev/null || true
    fi
done

echo "✅ Stop complete!"
echo ""
echo "VMs state:"
multipass list