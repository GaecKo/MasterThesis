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

## TLS 
TLS is by default not enabled. This is because no public domain is used, and therefore the certificates must be self-signed. This process can be tricky, and is therefore by default disabled. In the scope of this Master Thesis, we tested the TLS setup with the different devices that we had: `nuc1`, `nuc4`, `nuc8`. We developped some scripts (`api_gateway/setup_gateway_TLS.sh`, `backend/setup_backend_TLS.sh`, and `devices/setup_device_TLS.sh`), but these are tailored for the specific case of these nucs. One can however get inspiration from them to see how it would work. 
**Again**, as no public domain was used (which would have made things easier), the Java plugin also had to be adapted to use these self-signed certificates. The files 
* `java-plugins/edge-control/src/main/java/edge_control/translation/adapter/HttpForgery.java` 
* `java-plugins/edge-control/src/main/java/edge_control/translation/adapter/TlsHelper.java`

leverages these certificates, and should be fine-tuned to specific other use cases. 

## Traffic Overload Protection / nf-tables
The script `nftables-apisix.sh` creates the nf-tables used for traffic overload protection. The script contains values that can be modified depeding on the context and the topology of the used network. 

**Apply the nf-tables**:
```bash
sudo ./nftables-apisix.sh
```
**Reset the nf-tables**: 
```bash
sudo nft delete table ip apisix_guard
```
> At the end of the file `nftables-apisix.sh`, you will find instructions on how to test the overload protection.

## Quick Start (Local)

**Prerequisites:** Docker, Docker Compose, Java 17+, Maven

```bash
# 1. Start the API gateway stack
cd api_gateway
./api_gateway.sh

# 2. Configure routes and consumers (/!\ This sctipt will be ran automatically by the ./api_gateway.sh script - no need to run it)
# ./configure.sh

# 3. (Optional) Start a simulated device
cd ../devices/http_device
./http_device.sh
```

The gateway will be available at, admin key is `admin` (as defined in `conf/config.yaml`):
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
