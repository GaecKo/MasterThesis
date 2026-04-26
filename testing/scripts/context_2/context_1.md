# EdgeControl Performance Test Tool

Three scripts:
- `generate_configs.py` — generates and uploads device translation configs
- `locustfile.py` — Locust load definition with exact throughput control
- `run_test.sh` — orchestrator: SSH stats + warmup + capture + flat CSV row

## One-time setup

### 1. Python deps

```bash
pip install locust requests pyjwt
chmod +x run_test.sh
```

### 2. Passwordless SSH from backend NUC → gateway NUC

The orchestrator runs `docker stats` on the gateway via SSH to capture CPU/RAM
without installing Prometheus. Set it up once:

On the **backend NUC** (where you run `run_test.sh`):

```bash
# Generate an SSH key if you don't already have one
ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519

# Copy your public key to the gateway NUC (replace user as needed)
ssh-copy-id nuc4@nuc4-pc.local
# Will prompt for nuc4's password once

# Verify it works without a password
ssh nuc4@nuc4-pc.local "echo ok"
```

The user on the gateway NUC must be in the `docker` group to run `docker stats`
without sudo:

```bash
# On the gateway NUC, once:
sudo usermod -aG docker $USER
# Log out and back in for the group change to take effect
```

Test it end-to-end from the backend NUC:
```bash
ssh nuc4@nuc4-pc.local "docker stats --no-stream apisix"
```

If that works, you're ready.

## Per-scenario workflow

### 1. Configure the scenario on the gateway NUC

Set up architecture per the context table (plugin on/off, auth filter, etc.)
This is manual and depends on the scenario.

### 2. Edit and run `generate_configs.py`

Edit the two dictionaries at the top:

```python
DEVICE_COMMANDS = {
    "http_device_001": ["setBatteryOperation", "setChargeTarget"],
    "mqtt_device_001": ["setPower", "setMode"],
}
BACKEND_API_KEYS = {
    "backend_001": "abc123...",
}
```

Then run with the desired payload size:
```bash
python3 generate_configs.py --size medium   # 5-15 mappings per command
python3 generate_configs.py --size large    # 25-30 mappings per command
```

Output:
- `./generated_configs/<device_id>.json` — the config uploaded for each device
- `./generated_configs/_summary.json` — consumed by Locust
- Console prints the port assigned to each HTTP device — **start your Node.js listeners on those ports before running tests**

The script crashes with the error message on any upload failure.

### 3. Run the test

```bash
./run_test.sh <scenario_name> <nb_req_per_sec> <payload_size>
```

Example:
```bash
./run_test.sh scenarioD 100 medium
```

What happens:
1. SSH into the gateway, start `docker stats` polling every 2s → `docker_stats.csv`
2. Warmup: 2 minutes at `nb_req_per_sec` (no metrics captured)
3. Capture: 15 minutes at `nb_req_per_sec`, with Locust CSV + history
4. Stop stats collector
5. Aggregate Locust + docker stats into a single row appended to `./results/all_runs.csv`

## Output

Per-run directory `./results/<scenario>_<size>_<rps>rps_<timestamp>/`:
- `locust_stats.csv` — per-endpoint stats (the master CSV picks the Aggregated row)
- `locust_stats_history.csv` — time series for plotting
- `locust_failures.csv` — failed requests, if any
- `docker_stats.csv` — CPU/mem per container, one row per poll
- `warmup.log`, `capture.log`

Master CSV `./results/all_runs.csv` — **one row per run**, columns:
```
scenario, target_rps, payload_size, timestamp, num_users, capture_seconds,
request_count, failure_count, median_ms, avg_ms, min_ms, max_ms, avg_content_size,
achieved_rps, failures_per_sec,
p50, p66, p75, p80, p90, p95, p98, p99, p99_9, p99_99, p100,
<for each container>: cpu_avg, cpu_max, mem_avg, mem_max
```

## How exact req/sec is enforced

Locust's `constant_throughput(rate)` caps each virtual user to `rate` req/s.
With `NUM_USERS` users, total throughput = `NUM_USERS * (TARGET_RPS / NUM_USERS) = TARGET_RPS`.

`NUM_USERS` defaults to 50. This is large enough that each user can pace
slowly without becoming latency-bound — the rule of thumb is
`NUM_USERS >= TARGET_RPS × p99_latency_in_seconds`. If you see the achieved
RPS fall short of the target, bump `NUM_USERS`:

```bash
NUM_USERS=100 ./run_test.sh scenarioD 400 medium
```

## Other tunables

Override via environment variables:
- `GATEWAY_SSH_HOST`  — SSH target (default: `nuc4@nuc4-pc.local`)
- `TARGET_HOST`       — Gateway URL (default: `http://nuc4-pc.local:9080`)
- `CONTAINERS`        — containers to monitor (default: `apisix mongodb mosquitto etcd`)
- `WARMUP_SECONDS`    — default 120
- `CAPTURE_SECONDS`   — default 900
- `STATS_INTERVAL`    — docker stats poll interval (default: 2s)
- `NUM_USERS`         — Locust virtual users (default: 50)
- `MASTER_CSV`        — output CSV path (default: `./results/all_runs.csv`)

Example quick run:
```bash
CAPTURE_SECONDS=60 WARMUP_SECONDS=10 ./run_test.sh smoke_test 50 medium
```