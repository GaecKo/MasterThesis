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

Launches the complete multi-VM environment automatically. From the main folder (so `../` compared to current dir)

```bash
# in main dir
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

## Quick Start (Local)

**Prerequisites:** Docker, Docker Compose, Java 17+, Maven

```bash
# 1. Start the API gateway stack
cd api_gateway
./api_gateway.sh

# 2. Configure routes and consumers (This sctipt will be ran automatically by the ./api_gateway.sh script)
./configure.sh

# 3. (Optional) Start a simulated device
cd ../devices/http_device
./http_device.sh
```

The gateway will be available at:
- HTTP: `http://localhost:9080`
- HTTPS: `https://localhost:9443`
- Admin API: `http://localhost:9180`

---

## Debugging and dev
**Prerequisites:** Docker, Docker Compose, Java 17+, Maven

### Start the gateway
```bash
cd api_gateway
./api_gateway.sh
```

### Refresh and apply changes
```bash
# still in api_gateway/ dir
./refresh.sh
```
`./refresh.sh` will stop the dockers, recompile the jar file of Edge Control, and restart the gateway.


## Tear Down
In the main directory:
```bash
# Stop all VMs
./stop.sh

# Full cleanup (removes VMS, volumes and containers)
./cleanup.sh
```
