#!/bin/bash

# Run on NUC1 (backend): ./setup_backend_TLS.sh

GATEWAY_IP="192.168.50.4"
BACKEND_IP="192.168.50.1"

GATEWAY_HOSTNAME="nuc4-pc"
GATEWAY_DOMAIN="${GATEWAY_HOSTNAME}.local"

echo "=== Setting up Backend NUC (NUC1 - ${BACKEND_IP}) ==="

echo "[1/3] Adding ${GATEWAY_DOMAIN} → ${GATEWAY_IP} to /etc/hosts..."
if grep -q "${GATEWAY_DOMAIN}" /etc/hosts; then
  echo "Entry already exists in /etc/hosts, skipping."
else
  echo "${GATEWAY_IP}   ${GATEWAY_DOMAIN}" | sudo tee -a /etc/hosts
  echo "Added to /etc/hosts."
fi

echo "[2/3] Trusting the gateway certificate..."
if [ ! -f ~/server.crt ]; then
  echo "Error: ~/server.crt not found. Run ./setup_gateway.sh on NUC4 first."
  exit 1
fi
sudo cp ~/server.crt /usr/local/share/ca-certificates/apisix.crt
sudo update-ca-certificates
echo "Certificate trusted."

echo "[3/3] Testing HTTPS connection to gateway..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  https://${GATEWAY_DOMAIN}:9443/health || true)
echo "Response code: ${HTTP_CODE}"

echo ""
echo "=== Backend setup complete ==="
echo "You can now send requests to: https://${GATEWAY_DOMAIN}:9443"