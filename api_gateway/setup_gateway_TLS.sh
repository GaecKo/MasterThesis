#!/bin/bash
# Run on NUC4 (gateway): ./setup_gateway.sh

GATEWAY_IP="192.168.50.4"
BACKEND_IP="192.168.50.1"
DEVICE_IP="192.168.50.8"
GATEWAY_HOSTNAME="nuc4-pc"
GATEWAY_DOMAIN="${GATEWAY_HOSTNAME}.local"

echo "=== Setting up Gateway NUC (NUC4 - ${GATEWAY_IP}) ==="

# ── SSH server on NUC4 itself ──────────────────────────────
echo "[0/4] Ensuring SSH server is installed and running on NUC4..."
if ! dpkg -l openssh-server &>/dev/null; then
  sudo apt update && sudo apt install -y openssh-server
fi
sudo systemctl enable --now ssh
echo "SSH server ready."

# ── Copy existing SSH key to NUC1 and NUC8 ────────────────
echo "[1/4] Copying SSH key to NUC1 and NUC8..."
echo "Copying to NUC1 (you may be prompted for nuc1's password)..."
ssh-copy-id -o StrictHostKeyChecking=no nuc1@${BACKEND_IP}
echo "Copying to NUC8 (you may be prompted for nuc8's password)..."
ssh-copy-id -o StrictHostKeyChecking=no nuc8@${DEVICE_IP}

# ── TLS certificate ────────────────────────────────────────
echo "[2/4] Generating self-signed certificate for ${GATEWAY_DOMAIN}..."
mkdir -p conf
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout conf/server.key \
  -out conf/server.crt \
  -subj "/CN=${GATEWAY_DOMAIN}" \
  -addext "subjectAltName=DNS:${GATEWAY_DOMAIN},IP:${GATEWAY_IP}"
echo "Certificate generated."

# ── Register cert in APISIX ───────────────────────────────
echo "[3/4] Registering certificate in APISIX..."
server_cert=$(cat conf/server.crt)
server_key=$(cat conf/server.key)

curl http://127.0.0.1:9180/apisix/admin/ssls/1 \
  -H 'X-API-KEY: admin' -X PUT -d '{
    "id": "1",
    "sni": "'"${GATEWAY_DOMAIN}"'",
    "cert": "'"${server_cert}"'",
    "key": "'"${server_key}"'"
  }'
echo ""
echo "Certificate registered in APISIX."

# ── Distribute cert to NUC1 and NUC8 ─────────────────────
echo "[4/5] Copying certificate to NUC1 and NUC8..."
scp conf/server.crt nuc1@${BACKEND_IP}:~/server.crt || { echo "Error: scp to NUC1 failed."; exit 1; }
scp conf/server.crt nuc8@${DEVICE_IP}:~/server.crt  || { echo "Error: scp to NUC8 failed."; exit 1; }
echo "Certificate distributed."


# ── Configure Mosquitto for MQTTS ─────────────────────────
echo "[5/5] Configuring Mosquitto for MQTTS on port 8883..."

# Fix permissions for mosquitto container user (UID 1883)
MOSQUITTO_UID=$(docker exec mosquitto id -u mosquitto)
sudo chown ${MOSQUITTO_UID}:${MOSQUITTO_UID} conf/server.key
sudo chown ${MOSQUITTO_UID}:${MOSQUITTO_UID} conf/server.crt
sudo chmod 600 conf/server.key
sudo chmod 644 conf/server.crt

docker restart mosquitto

echo "=== Gateway setup complete ==="