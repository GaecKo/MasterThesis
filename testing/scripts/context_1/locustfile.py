"""
Locust load test for the EdgeControl gateway.

Uses constant_throughput to enforce an exact requests-per-second rate.
Each virtual user sends POST /command requests, round-robin across all
(backend, device, command) tuples discovered from the generated configs.

Usage:
    TARGET_RPS=100 NUM_USERS=20 \\
        locust -f locustfile.py --host=http://nuc4-pc.local:9080 \\
               -u 20 -r 20 -t 900s --csv=./out --headless

Environment variables (required):
    TARGET_RPS        Total req/sec across all users (integer)
    NUM_USERS         Number of virtual users (integer, must match -u)
    CONFIGS_SUMMARY   Path to _summary.json (default: ./generated_configs/_summary.json)
"""

import itertools
import json
import os
import random
import threading
from pathlib import Path

from locust import HttpUser, constant_throughput, task, events

# | ================= Rate control ================= |

TARGET_RPS = float(os.environ["TARGET_RPS"])
NUM_USERS  = int(os.environ["NUM_USERS"])

# constant_throughput(rate) means each user sends exactly `rate` requests per second.
# Total throughput = NUM_USERS * PER_USER_RPS = TARGET_RPS.
PER_USER_RPS = TARGET_RPS / NUM_USERS

# | ================= Load test plan ================= |

SUMMARY_PATH = Path(os.environ.get(
    "CONFIGS_SUMMARY",
    "./generated_configs/_summary.json"))

if not SUMMARY_PATH.exists():
    raise RuntimeError(
        f"Summary file not found at {SUMMARY_PATH}. "
        f"Run generate_configs.py first or set CONFIGS_SUMMARY.")

with SUMMARY_PATH.open() as f:
    SUMMARY = json.load(f)

BACKENDS = SUMMARY["backends"]   # backend_id -> api_key
DEVICES  = SUMMARY["devices"]    # device_id -> { adapter, commands: {name -> [param_names]} }

# Flat list of every (backend_id, device_id, command_name, param_names) tuple
# so a single round-robin iterator can walk it.
REQUEST_PLAN: list[tuple[str, str, str, list[str]]] = []
for backend_id in BACKENDS:
    for device_id, device_info in DEVICES.items():
        for command_name, param_names in device_info["commands"].items():
            REQUEST_PLAN.append((backend_id, device_id, command_name, param_names))

if not REQUEST_PLAN:
    raise RuntimeError("Request plan is empty — check generated_configs/_summary.json")

print(f"[locust] Plan: {len(REQUEST_PLAN)} (backend, device, command) combinations")
print(f"[locust] Target: {TARGET_RPS} req/s total via {NUM_USERS} users "
      f"({PER_USER_RPS:.3f} req/s per user)")

# Shared iterator across all users — a lock keeps the round-robin strict under concurrency
_plan_iter = itertools.cycle(REQUEST_PLAN)
_plan_lock = threading.Lock()

def next_request():
    """Returns the next (backend_id, device_id, command_name, param_names) tuple."""
    with _plan_lock:
        return next(_plan_iter)

# | ================= Param value generation ================= |

def sample_param_value(param_name: str):
    """Generates a realistic value for a given param name based on its semantics."""
    if param_name in {"startAt", "endAt"}:
        return "2024-01-01T00:00:00Z"
    if param_name in {"activePower", "reactivePower", "apparentPower"}:
        return random.randint(1000, 50000)
    if param_name in {"frequency"}:
        return round(random.uniform(49.5, 50.5), 2)
    if param_name in {"voltage"}:
        return random.randint(220, 240)
    if param_name in {"current"}:
        return round(random.uniform(10.0, 100.0), 1)
    if param_name in {"maxRate", "minRate", "rampRate", "deadband", "droopSetting"}:
        return round(random.uniform(0.1, 10.0), 2)
    if param_name in {"percentage", "targetSoC"}:
        return random.randint(0, 100)
    if param_name in {"maxCurrent"}:
        return round(random.uniform(10.0, 100.0), 1)
    if param_name == "chargeMode":
        return random.choice(["fast", "standard", "eco"])
    if param_name == "priority":
        return random.choice(["low", "medium", "high"])
    if param_name in {"overrideExisting", "notifyOnComplete", "auditTrailEnabled",
                     "dryRun", "forceExecution"}:
        return random.choice([True, False])
    if param_name == "assetIdentifiers":
        return [f"asset_{random.randint(1, 100)}"]
    if param_name == "correlationId":
        return f"corr_{random.randint(100000, 999999)}"
    if param_name == "duration":
        return random.randint(60, 3600)
    # Default string fallback
    return f"val_{random.randint(0, 1000)}"

# | ================= Locust user ================= |

class BackendUser(HttpUser):
    """
    Each virtual user acts as a backend sending commands to devices.
    constant_throughput caps each user to PER_USER_RPS, giving exact total throughput.
    """
    # Returns requests-per-second per user. Locust enforces this internally.
    wait_time = constant_throughput(PER_USER_RPS)

    @task
    def send_command(self):
        backend_id, device_id, command_name, param_names = next_request()

        params = {name: sample_param_value(name) for name in param_names}
        payload = {
            "gatewayDeviceId": device_id,
            "command":         command_name,
            "params":          params,
        }
        headers = {
            "Content-Type": "application/json",
            "apikey":       BACKENDS[backend_id],
        }

        # Group requests by adapter type so the CSV separates HTTP vs MQTT stats
        with self.client.post(
                "/command",
                json=payload,
                headers=headers,
                name=f"/command [{DEVICES[device_id]['adapter']}]",
                catch_response=True) as resp:
            if resp.status_code not in (200, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")

# | ================= Test lifecycle ================= |

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    print(f"[locust] Test starting against {environment.host}")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust] Test stopped.")