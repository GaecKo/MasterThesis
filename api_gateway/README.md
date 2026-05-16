# API Gateway — Master Thesis

An **Apache APISIX**-based API gateway designed for secure device management, featuring protocol translation (HTTP & MQTT), JWT-based authentication, device onboarding, and backend forwarding. Built as part of a master's thesis on renewable energy device security and API gateway architectures.

---

## Folder Structure

```
api_gateway/
├── api_gateway.sh          # Standalone launch script
├── refresh.sh              # Refresh the api gateway docker container
├── nftables-apisix.sh      # Firewall script         
├── configure.sh            # Exposed routes configuration
├── setup_gateway_TLS.sh    # TLS setup for the api gateway
├── docker-compose.yml      # Full service stack definition
├── Dockerfile              # Custom APISIX image (includes Java plugin)
├── conf/
│   └──config.yaml         # APISIX core configuration
├── brokers/
│   └── mosquitto/
│       └── mosquitto.conf  # MQTT broker configuration
└── java-plugins/
    └── edge-control/       # Custom Java plugin → see its own README
```

---

## Deployment Modes

This project supports two deployment modes:

### Mode 1 — Full VM Setup (recommended for thesis reproduction)

Launches the complete multi-VM environment automatically.

```bash
chmod +x main.sh
./main.sh
```

This will provision VMs and deploy all services (gateway, backends, devices) across them.

### Mode 2 — Standalone 

Run each component directly on your machine without any VM setup. For example, to run only the API gateway:

```bash
cd api_gateway
chmod +x api_gateway.sh
./api_gateway.sh
```

See each component's README for its specific standalone instructions.

---

## Components

| Component | Description | README |
|---|---|---|
| API Gateway | APISIX + etcd + MongoDB + Mosquitto | [**API Gateway**](api_gateway/README.md) |
| Edge Control Plugin | Custom Java plugin for auth & translation | [**Edge Control (Java Plugin)**](api_gateway/java-plugins/edge-control/README.md) |
| Devices | HTTP and MQTT IoT device simulators | [**Devices**](devices/README.md) |
| Backend | HTTP backend simulators | [**Backend**](backend/README.md) |

---

## Quick Start (Local)

**Prerequisites:** Docker, Docker Compose, Java 17+, Maven

```bash
# 1. Start the API gateway stack
cd api_gateway
./api_gateway.sh

# 2. Configure routes and consumers(THis sctipt will be ran automatically by the ./api_gateway.sh script)
./configure.sh

# 3. (Optional) Run a simulated device
cd ../devices/http_device
./http_device.sh
```

The gateway will be available at:
- HTTP: `http://localhost:9080`
- HTTPS: `https://localhost:9443`
- Admin API: `http://localhost:9180`

---

## Tear Down

```bash
# Stop all services
./stop.sh

# Full cleanup (removes volumes and containers)
./cleanup.sh
```
