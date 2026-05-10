"""
Context 2 — Overload / ramp-up test.

Pre-builds a pool of serialised payloads at startup so each task just picks
one from the pool instead of building a dict + calling json.dumps() + uuid4()
on every request. This removes the Python CPU bottleneck on nuc1 and allows
the distributed worker mode to scale past ~1400 req/s.

Compatible with Locust distributed mode — run_test_rampup.sh handles
spawning master + workers automatically when NUM_WORKERS > 1.

Run:
    START_RPS=10 END_RPS=2000 STEP_RPS=50 STEP_DURATION_S=20 NUM_WORKERS=3 \\
        ./run_test_rampup.sh context2_full medium
"""

import csv
import itertools
import json
import os
import random
import threading
import time
import uuid
from pathlib import Path

import urllib3
from locust import HttpUser, LoadTestShape, constant_throughput, events, task

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# | ================= User-provided backend API keys ================= |

BACKEND_API_KEYS: dict[str, str] = {
    "backend_0ac63729-9e64-4c7f-8c77-46f9f5755166": "aPyDBQ9-zuCVC-wHPA161VI2zcgNutLntckj1F7by4I",
    "backend_052e1ef9-189d-4905-8827-e3185ea49d35": "oGBDK-aGWV4XAmF2GK1wzdSlsJfHRyJY2vRNRRY09PE",
    "backend_84a92f7d-a962-465e-94a4-721517b037b6": "wRSK9A8RJ3OqhfJ26hC1C146zzxdcp7upadRLcjQeoQ",
    "backend_589f04d4-b279-4e5c-b6f1-7d7faa70b113": "NhlXvYlKtDKD972qDGQV0CTAPrUrdXUagV9W4vxJH7w",
}

# | ================= Load test plan from summary ================= |

SUMMARY_PATH = Path(os.environ.get(
    "CONFIGS_SUMMARY", "./generated_configs/_summary.json"))

if not SUMMARY_PATH.exists():
    raise RuntimeError(
        f"Summary not found at {SUMMARY_PATH} — run generate_configs.py first.")

with SUMMARY_PATH.open() as f:
    SUMMARY = json.load(f)

DEVICES = SUMMARY["devices"]

if not BACKEND_API_KEYS:
    raise RuntimeError("BACKEND_API_KEYS is empty — fill in the dict at the top.")

# Flat round-robin plans per adapter type
HTTP_PLAN: list[tuple] = []
MQTT_PLAN: list[tuple] = []

for backend_id, api_key in BACKEND_API_KEYS.items():
    for device_id, device_info in DEVICES.items():
        plan = HTTP_PLAN if device_info["adapter"] == "http" else MQTT_PLAN
        for command_name, param_names in device_info["commands"].items():
            plan.append((backend_id, api_key, device_id, command_name, param_names))

# | ================= Ramp-up shape parameters ================= |

START_RPS       = float(os.environ.get("START_RPS",       "10"))
END_RPS         = float(os.environ.get("END_RPS",         "300"))
STEP_RPS        = float(os.environ.get("STEP_RPS",        "10"))
STEP_DURATION_S = float(os.environ.get("STEP_DURATION_S", "30"))
PER_USER_RPS    = float(os.environ.get("PER_USER_RPS",    "1.0"))

HTTP_WEIGHT = max(sum(1 for d in DEVICES.values() if d["adapter"] == "http"), 1)
MQTT_WEIGHT = max(sum(1 for d in DEVICES.values() if d["adapter"] == "mqtt"), 1)

# | ================= Pre-built payload pool ================= |
# Build once at module load, workers pick randomly at task time.
# Eliminates per-request dict construction, json.dumps(), and uuid4() calls —
# the main CPU bottleneck when trying to push past ~1400 req/s from nuc1.

POOL_SIZE = 500   # entries per protocol — enough variety, fast to build

def sample_param_value(param_name: str):
    if param_name in {"startAt", "endAt"}:           return "2024-01-01T00:00:00Z"
    if param_name in {"activePower", "reactivePower", "apparentPower"}:
                                                      return random.randint(1000, 50000)
    if param_name == "frequency":                     return round(random.uniform(49.5, 50.5), 2)
    if param_name == "voltage":                       return random.randint(220, 240)
    if param_name == "current":                       return round(random.uniform(10.0, 100.0), 1)
    if param_name in {"maxRate", "minRate", "rampRate", "deadband", "droopSetting"}:
                                                      return round(random.uniform(0.1, 10.0), 2)
    if param_name in {"percentage", "targetSoC"}:     return random.randint(0, 100)
    if param_name == "maxCurrent":                    return round(random.uniform(10.0, 100.0), 1)
    if param_name == "chargeMode":                    return random.choice(["fast", "standard", "eco"])
    if param_name == "priority":                      return random.choice(["low", "medium", "high"])
    if param_name in {"overrideExisting", "notifyOnComplete", "auditTrailEnabled",
                      "dryRun", "forceExecution"}:    return random.choice([True, False])
    if param_name == "assetIdentifiers":              return [f"asset_{random.randint(1, 100)}"]
    if param_name == "correlationId":                 return str(uuid.uuid4())
    if param_name == "duration":                      return random.randint(60, 3600)
    return f"val_{random.randint(0, 1000)}"

def _build_pool(plan: list[tuple], size: int) -> list[tuple[str, bytes]]:
    """Returns list of (api_key, payload_bytes) tuples, pre-serialised."""
    if not plan:
        return []
    pool = []
    cycle = itertools.cycle(plan)
    for _ in range(size):
        backend_id, api_key, device_id, command_name, param_names = next(cycle)
        payload = {
            "gatewayDeviceId": device_id,
            "command":         command_name,
            "params":          {n: sample_param_value(n) for n in param_names},
        }
        pool.append((api_key, json.dumps(payload).encode()))
    return pool

print("[locust/ramp] Building payload pools...")
_HTTP_POOL = _build_pool(HTTP_PLAN, POOL_SIZE)
_MQTT_POOL = _build_pool(MQTT_PLAN, POOL_SIZE)
print(f"[locust/ramp] HTTP pool: {len(_HTTP_POOL)} entries  "
      f"MQTT pool: {len(_MQTT_POOL)} entries")

# | ================= Ramp-up shape ================= |

_shape_start_time: float = None

class RampUpShape(LoadTestShape):
    def tick(self):
        global _shape_start_time
        if _shape_start_time is None:
            _shape_start_time = time.perf_counter()

        elapsed    = time.perf_counter() - _shape_start_time
        step_index = int(elapsed // STEP_DURATION_S)
        target_rps = START_RPS + step_index * STEP_RPS

        if target_rps > END_RPS:
            return None

        user_count = max(1, round(target_rps / PER_USER_RPS))
        return user_count, max(1, user_count)

# | ================= Users ================= |

class HttpRampUser(HttpUser):
    """HTTP device commands — picks a pre-built payload from the pool."""
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = HTTP_WEIGHT

    @task
    def send_command(self):
        if not _HTTP_POOL:
            return
        api_key, payload_bytes = random.choice(_HTTP_POOL)
        with self.client.post(
                "/command",
                data=payload_bytes,
                headers={"Content-Type": "application/json", "apikey": api_key},
                name="ramp/http",
                verify=False,
                catch_response=True) as resp:
            if resp.status_code not in (200, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")


class MqttRampUser(HttpUser):
    """MQTT device commands — picks a pre-built payload from the pool."""
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = MQTT_WEIGHT

    @task
    def send_command(self):
        if not _MQTT_POOL:
            return
        api_key, payload_bytes = random.choice(_MQTT_POOL)
        with self.client.post(
                "/command",
                data=payload_bytes,
                headers={"Content-Type": "application/json", "apikey": api_key},
                name="ramp/mqtt",
                verify=False,
                catch_response=True) as resp:
            if resp.status_code not in (200, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")

# | ================= Raw latency listener ================= |

_raw_csv_path   = None
_raw_csv_file   = None
_raw_csv_writer = None
_raw_csv_lock   = threading.Lock()

@events.init.add_listener
def init_raw_csv(environment, **kwargs):
    global _raw_csv_path, _raw_csv_file, _raw_csv_writer
    # In distributed mode only the master writes the CSV
    if hasattr(environment, "parsed_options") and \
       getattr(environment.parsed_options, "worker", False):
        return
    results_dir   = os.environ.get("RESULTS_DIR", ".")
    _raw_csv_path = os.path.join(results_dir, "raw_latencies.csv")
    _raw_csv_file = open(_raw_csv_path, "w", newline="")
    _raw_csv_writer = csv.writer(_raw_csv_file)
    _raw_csv_writer.writerow(
        ["timestamp_ms", "protocol", "name", "response_time_ms", "success"])
    print(f"[locust] Raw latency log: {_raw_csv_path}")

@events.request.add_listener
def on_request(request_type, name, response_time, exception, **kwargs):
    if _raw_csv_writer is None:
        return
    with _raw_csv_lock:
        _raw_csv_writer.writerow([
            int(time.time() * 1000),
            request_type,
            name,
            round(response_time, 3),
            exception is None,
        ])

@events.quitting.add_listener
def close_raw_csv(environment, **kwargs):
    if _raw_csv_file:
        _raw_csv_file.flush()
        _raw_csv_file.close()
        print(f"[locust] Raw latencies saved to {_raw_csv_path}")

# | ================= Startup info ================= |

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    steps   = int((END_RPS - START_RPS) / STEP_RPS) + 1
    total_s = steps * STEP_DURATION_S
    print(f"[locust/ramp] Ramp : {START_RPS} → {END_RPS} req/s  "
          f"step={STEP_RPS}  {steps} steps × {STEP_DURATION_S}s = {total_s:.0f}s")
    print(f"[locust/ramp] Per-user RPS : {PER_USER_RPS}")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust/ramp] Test stopped.")