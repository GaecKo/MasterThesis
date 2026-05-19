# Backend

This folder contains a simulated backend service used to test the API gateway. It is a lightweight Node.js/Express server that exposes HTTP and HTTPS endpoints, and acts as the upstream service that receives forwarded requests from the gateway.

---

## Deployment Modes

**As part of the full setup** — `main.sh` at the repo root handles everything automatically.

**Standalone** — run the backend directly on your machine:

```bash
cd backend
chmod +x backend.sh
./backend.sh
```

> ⚠️ Run these commands from within the right machine! So mostly from the backend VM if you used ./main.sh, or directly on your machine if you want to. 

---

## TLS Setup

If you want TLS to be enabled (can be tricky to make work!), some helper scripts exist.

The backend communicates with the APISIX gateway over TLS. Before starting the container, run the TLS setup script to generate the required certificates and trust the gateway's certificate:



```bash
chmod +x setup_backend_TLS.sh
./setup_backend_TLS.sh
```

This will:
- Generate the backend's own certificate (`backend.crt` / `backend.key`) under `~/certs/`
- Install the APISIX gateway certificate (`apisix.crt`) into the system trust store

The launch script (`backend.sh`) then copies these certs into the Docker build context before starting the container.

> ⚠️ Run `setup_backend_TLS.sh` **before** `backend.sh`, otherwise the container will fail to start with HTTPS.

---

## Folder Structure

```
backends/
├── backend.js            # Main Express server (HTTP + HTTPS)
├── backend.sh            # Build and launch script
├── Dockerfile            # Container image
├── package.json          # Node.js dependencies
└── setup_backend_TLS.sh  # TLS certificate setup
```

---

## Configuration

The backend is configured via environment variables set in `backend.sh`:

| Variable | Description | Example |
|---|---|---|
| `BACKEND_IP` | IP of the machine running the backend | `192.168.50.1` |
| `APISIX_IP` | IP of the APISIX gateway | `192.168.50.10` |

These are passed automatically by `backend.sh` and needs to be set to match the correct IP adresses

---
 
## Exposed Endpoints
 
The backend listens on two ports:
 
| Port | Protocol |
|---|---|
| `8000` | HTTP |
| `8443` | HTTPS (requires TLS setup) |
