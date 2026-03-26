#!/bin/bash
# Run on NUC4 (gateway): ./setup_gateway_TLS.sh

GATEWAY_IP="192.168.50.4"
BACKEND_IP="192.168.50.1"
DEVICE_IP="192.168.50.8"
GATEWAY_HOSTNAME="nuc4-pc"
GATEWAY_DOMAIN="${GATEWAY_HOSTNAME}.local"

echo "=== Setting up Gateway NUC (NUC4 - ${GATEWAY_IP}) ==="

# ── SSH server on NUC4 ────────────────────────────────────
echo "[0/7] Ensuring SSH server is installed and running on NUC4..."
if ! dpkg -l openssh-server &>/dev/null; then
  sudo apt update && sudo apt install -y openssh-server
fi
sudo systemctl enable --now ssh
echo "SSH server ready."

# ── Copy SSH key to NUC1 and NUC8 ────────────────────────
echo "[1/7] Copying SSH key to NUC1 and NUC8..."
ssh-copy-id -o StrictHostKeyChecking=no nuc1@${BACKEND_IP}
ssh-copy-id -o StrictHostKeyChecking=no nuc8@${DEVICE_IP}

# ── TLS certificate for APISIX ───────────────────────────
echo "[2/7] Generating self-signed certificate for ${GATEWAY_DOMAIN}..."
mkdir -p conf
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout conf/server.key \
  -out conf/server.crt \
  -subj "/CN=${GATEWAY_DOMAIN}" \
  -addext "subjectAltName=DNS:${GATEWAY_DOMAIN},IP:${GATEWAY_IP}" \
  -addext "basicConstraints=critical,CA:TRUE"
echo "Certificate generated."

# ── Register cert in APISIX ───────────────────────────────
echo "[3/7] Registering certificate in APISIX..."
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

# ── Distribute gateway cert to NUC1 and NUC8 ─────────────
echo "[4/7] Copying gateway certificate to NUC1 and NUC8..."
scp conf/server.crt nuc1@${BACKEND_IP}:~/server.crt || { echo "Error: scp to NUC1 failed."; exit 1; }
scp conf/server.crt nuc8@${DEVICE_IP}:~/server.crt  || { echo "Error: scp to NUC8 failed."; exit 1; }
echo "Certificate distributed."

# ── Configure Mosquitto for MQTTS ─────────────────────────
echo "[5/7] Configuring Mosquitto for MQTTS on port 8883..."
MOSQUITTO_UID=$(docker exec mosquitto id -u mosquitto)
sudo chown ${MOSQUITTO_UID}:${MOSQUITTO_UID} conf/server.key
sudo chown ${MOSQUITTO_UID}:${MOSQUITTO_UID} conf/server.crt
sudo chmod 600 conf/server.key
sudo chmod 644 conf/server.crt
docker restart mosquitto
echo "Mosquitto configured."

# ── Pull certs FROM NUC1 and NUC8 instead of waiting for push ────
echo "[6/7] Pulling certificates from NUC1 and NUC8..."
scp nuc1@${BACKEND_IP}:~/certs/server.crt ~/nuc1.crt || { echo "Error: scp from NUC1 failed."; exit 1; }
scp nuc8@${DEVICE_IP}:~/certs/server.crt ~/nuc8.crt  || { echo "Error: scp from NUC8 failed."; exit 1; }
echo "Certificates pulled."

# ── Copy NUC1/NUC8 certs into APISIX config folder ───────
echo "[7/7] Trusting NUC1 and NUC8 certificates in APISIX..."
cp ~/nuc1.crt conf/nuc1.crt
cp ~/nuc8.crt conf/nuc8.crt
docker compose build apisix
docker compose up -d apisix
echo "APISIX restarted with NUC1 and NUC8 certs trusted."

echo ""
echo "=== Gateway setup complete ==="