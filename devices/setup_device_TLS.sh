#!/bin/bash
# Run on NUC8 (device): ./setup_device_TLS.sh

GATEWAY_IP="192.168.50.4"
DEVICE_IP="192.168.50.8"
GATEWAY_HOSTNAME="nuc4-pc"
GATEWAY_DOMAIN="${GATEWAY_HOSTNAME}.local"

echo "=== Setting up Device NUC (NUC8 - ${DEVICE_IP}) ==="

# ── SSH server ────────────────────────────────────────────
echo "[1/5] Ensuring SSH server is installed and running..."
if ! dpkg -l openssh-server &>/dev/null; then
  sudo apt update && sudo apt install -y openssh-server
fi
sudo systemctl enable --now ssh
echo "SSH server ready."

# ── /etc/hosts ────────────────────────────────────────────
echo "[2/5] Adding ${GATEWAY_DOMAIN} → ${GATEWAY_IP} to /etc/hosts..."
if grep -q "${GATEWAY_DOMAIN}" /etc/hosts; then
  echo "Entry already exists, skipping."
else
  echo "${GATEWAY_IP}   ${GATEWAY_DOMAIN}" | sudo tee -a /etc/hosts
  echo "Added to /etc/hosts."
fi

# ── Trust gateway certificate ─────────────────────────────
echo "[3/5] Trusting the gateway certificate..."
if [ ! -f ~/server.crt ]; then
  echo "Error: ~/server.crt not found. Run ./setup_gateway_TLS.sh on NUC4 first."
  exit 1
fi
echo "Certificate found."
sudo cp ~/server.crt /usr/local/share/ca-certificates/apisix.crt
sudo update-ca-certificates
echo "Certificate trusted."

# ── Generate NUC8 cert ────────────────────────────────────
echo "[4/5] Generating certificate for NUC8..."
mkdir -p ~/certs
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout ~/certs/server.key \
  -out ~/certs/server.crt \
  -subj "/CN=nuc8-pc.local" \
  -addext "subjectAltName=DNS:nuc8-pc.local,IP:${DEVICE_IP}"
echo "Certificate generated."

# ── Test HTTPS connection to gateway ──────────────────────
echo "[5/5] Testing HTTPS connection to gateway..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  https://${GATEWAY_DOMAIN}:9443/health || true)
echo "Response code: ${HTTP_CODE}"

echo ""
echo "=== Device setup complete ==="
echo "You can now send requests to: https://${GATEWAY_DOMAIN}:9443"