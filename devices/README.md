# Devices

This folder contains two simulated IoT devices used to test the API gateway. One communicates over HTTP, the other over MQTT.

---

## Deployment Modes

Like the rest of the project, devices can be run in two ways:

**As part of the full setup** — `main.sh` at the repo root handles everything automatically.

**Standalone** — run each device script directly on your machine:

```bash
cd devices/http_device
chmod +x http_device.sh
./http_device.sh

# or

cd devices/mqtt_device
chmod +x mqtt_device.sh
./mqtt_device.sh
```

> ⚠️ Run these commands from within the right machine! So mostly from the devices VM if you used ./main.sh

---

## TLS Setup

Both devices require a certificate to communicate with the APISIX gateway over TLS. A helper script is provided:

```bash
chmod +x setup_device_TLS.sh
./setup_device_TLS.sh
```

This will:
- Generate the backend's own certificate (`device.crt` / `device.key`) under `~/certs/`
- Install the APISIX gateway certificate (`apisix.crt`) into the system trust store


---

## HTTP Device (`http_device/`)

A Node.js device that communicates with the gateway using standard HTTP.

| File | Description |
|---|---|
| `http_device.js` | Main device script |
| `http_device.sh` | Launch script (sets env vars and starts the device) |
| `Dockerfile` | Container image for the device |
| `package.json` | Node.js dependencies |

---

> **Important:** In http_device.sh, the command bellow need to be updated by replacing the **$DEVICES_IP** , **$APISIX_IP** by the correct values and fill the **DEVICE_ID field by the gatewayDeviceId of the device that was added during the onboarding.
```sh
sudo docker run -d \
    --name http-device-app \
    --network host \
    -e HTTP_DEVICE_IP=$DEVICES_IP \
    -e APISIX_IP=$APISIX_IP \
    -e INTERVAL_MS=20000 \
    -e DEVICE_ID=device_382109bd-7428-4cb9-b075-9a0ef2041560 \
    http-device-app
```

## MQTT Device (`mqtt_device/`)

A Node.js device that communicates with the gateway's Mosquitto broker using the MQTT protocol.

| File | Description |
|---|---|
| `mqtt_device.js` | Main device script |
| `mqtt_device.sh` | Launch script |
| `Dockerfile` | Container image for the device |
| `package.json` | Node.js dependencies |

> **Important:** As for the HTTP device, the hardocoded values need to be replaced by actual values related to an onboarded device
```sh
docker run -d \
  --name mqtt-device-app \
  --network host \
  --restart unless-stopped \
  -e DEVICE_ID=device_ce66dadb-8ba5-4989-aeaa-b4f2bb8d15c8 \
  -e API_KEY=Rgh5Cwca_UHVak_12BCedSIv0C_chETTZy_WqftZrbE \
  -e BROKER_URL="mqtts://nuc4-pc.local:8883" \
  -e INTERVAL_MS=20000 \
  mqtt-device-app
```

**MQTT broker connection** (from the device VM or local machine):

| Parameter | Value |
|---|---|
| Host | Gateway IP of the host |
| Port | `1883` (plain) / `8883` (TLS) |
