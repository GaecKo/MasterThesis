#!/bin/bash

# Run on NUC4 (gateway): ./setup_gateway_TLS.sh

GATEWAY_IP="192.168.50.4"
BACKEND_IP="192.168.50.1"
DEVICE_IP="192.168.50.8"

GATEWAY_HOSTNAME="nuc4-pc"
GATEWAY_DOMAIN="${GATEWAY_HOSTNAME}.local"

echo "=== Setting up Gateway NUC (NUC4 - ${GATEWAY_IP}) ==="

echo "[1/3] Generating self-signed certificate for ${GATEWAY_DOMAIN}..."
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout conf/server.key \
  -out conf/server.crt \
  -subj "/CN=${GATEWAY_DOMAIN}" \
  -addext "subjectAltName=DNS:${GATEWAY_DOMAIN},IP:${GATEWAY_IP}"
echo "Certificate generated."

echo "[2/3] Registering certificate in APISIX..."
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

echo "[3/3] Copying certificate to NUC1 and NUC8..."
scp conf/server.crt ubuntu@${BACKEND_IP}:~/server.crt
scp conf/server.crt ubuntu@${DEVICE_IP}:~/server.crt
echo "Certificate distributed."

echo "=== Gateway setup complete ==="