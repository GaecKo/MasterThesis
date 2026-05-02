"""
Context 2 — Overload / ramp-up test.

Gradually increases throughput in steps, capturing latency at each level.
Produces a latency-vs-throughput dataset: as RPS increases, latency rises,
and eventually throughput plateaus while latency keeps climbing.

The command mechanism is identical to scenario E (full stack: AuthN/Z +
translation). Backend API keys and device definitions come from _summary.json.

Ramp-up shape:
    START_RPS      →  END_RPS  in increments of STEP_RPS
    Each step holds for STEP_DURATION_S seconds before incrementing.

    Example: START=10, END=200, STEP=10, DURATION=30
    → 19 steps × 30s = 570s total run time
    → data points at 10, 20, 30 ... 200 req/s

Output: results/<name>/locust_stats_history.csv contains achieved_rps and
latency percentiles per 2-second window — use this for the Y/X plot.
raw_latencies.csv has every individual request for box plots.

Run:
    START_RPS=10 END_RPS=300 STEP_RPS=10 STEP_DURATION_S=30 \\
        LOCUST_FILE=locustfile_rampup.py \\
        ./run_test_rampup.sh context2_scenarioE medium

Dependencies:
    pip install locust
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

_http_iter = itertools.cycle(HTTP_PLAN) if HTTP_PLAN else None
_mqtt_iter = itertools.cycle(MQTT_PLAN) if MQTT_PLAN else None
_http_lock = threading.Lock()
_mqtt_lock = threading.Lock()

def next_http():
    with _http_lock:
        return next(_http_iter)

def next_mqtt():
    with _mqtt_lock:
        return next(_mqtt_iter)

# | ================= Ramp-up shape parameters ================= |

START_RPS       = float(os.environ.get("START_RPS",       "10"))
END_RPS         = float(os.environ.get("END_RPS",         "300"))
STEP_RPS        = float(os.environ.get("STEP_RPS",        "10"))
STEP_DURATION_S = float(os.environ.get("STEP_DURATION_S", "30"))

# Each user sends exactly PER_USER_RPS requests per second via constant_throughput.
# The shape controls how many users are active — total RPS = users × PER_USER_RPS.
# Keep PER_USER_RPS small so we can hit low RPS targets with at least a few users.
PER_USER_RPS = float(os.environ.get("PER_USER_RPS", "1.0"))

# Weight by device count, not plan size, to get equal HTTP/MQTT split
HTTP_WEIGHT = max(sum(1 for d in DEVICES.values() if d["adapter"] == "http"), 1)
MQTT_WEIGHT = max(sum(1 for d in DEVICES.values() if d["adapter"] == "mqtt"), 1)

# | ================= Ramp-up shape ================= |

# Module-level start time — more reliable than a class variable across Locust internals
_shape_start_time: float = None

class RampUpShape(LoadTestShape):
    """
    Staircase ramp-up: holds each RPS level for STEP_DURATION_S seconds,
    then increments by STEP_RPS until END_RPS is reached.

    Total run time = ceil((END_RPS - START_RPS) / STEP_RPS + 1) × STEP_DURATION_S
    """

    def tick(self):
        """
        Called every second by Locust. Returns (user_count, spawn_rate) for
        the current moment, or None to stop the test.
        """
        global _shape_start_time
        if _shape_start_time is None:
            _shape_start_time = time.perf_counter()

        elapsed    = time.perf_counter() - _shape_start_time
        step_index = int(elapsed // STEP_DURATION_S)
        target_rps = START_RPS + step_index * STEP_RPS

        if target_rps > END_RPS:
            return None

        user_count = max(1, round(target_rps / PER_USER_RPS))
        spawn_rate = max(1, user_count)   # spawn all at once for clean step start

        return user_count, spawn_rate

# | ================= Param value generator ================= |

def sample_param_value(param_name: str):
    """Generates a realistic value for a param based on its name."""
    if param_name in {"startAt", "endAt"}:
        return "2024-01-01T00:00:00Z"
    if param_name in {"activePower", "reactivePower", "apparentPower"}:
        return random.randint(1000, 50000)
    if param_name == "frequency":
        return round(random.uniform(49.5, 50.5), 2)
    if param_name == "voltage":
        return random.randint(220, 240)
    if param_name == "current":
        return round(random.uniform(10.0, 100.0), 1)
    if param_name in {"maxRate", "minRate", "rampRate", "deadband", "droopSetting"}:
        return round(random.uniform(0.1, 10.0), 2)
    if param_name in {"percentage", "targetSoC"}:
        return random.randint(0, 100)
    if param_name == "maxCurrent":
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
        return str(uuid.uuid4())
    if param_name == "duration":
        return random.randint(60, 3600)
    return f"val_{random.randint(0, 1000)}"

# | ================= Users ================= |

class HttpRampUser(HttpUser):
    """HTTP device commands via the full plugin stack."""
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = HTTP_WEIGHT

    @task
    def send_command(self):
        if not HTTP_PLAN:
            return
        backend_id, api_key, device_id, command_name, param_names = next_http()
        payload = {
            "gatewayDeviceId": device_id,
            "command":         command_name,
            "params":          {n: sample_param_value(n) for n in param_names},
        }
        with self.client.post(
                "/command",
                json=payload,
                headers={"Content-Type": "application/json", "apikey": api_key},
                name="ramp/http",
                verify=False,
                catch_response=True) as resp:
            if resp.status_code not in (200, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")


class MqttRampUser(HttpUser):
    """MQTT device commands via the full plugin stack (HTTP POST, plugin handles MQTT)."""
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = MQTT_WEIGHT

    @task
    def send_command(self):
        if not MQTT_PLAN:
            return
        backend_id, api_key, device_id, command_name, param_names = next_mqtt()
        payload = {
            "gatewayDeviceId": device_id,
            "command":         command_name,
            "params":          {n: sample_param_value(n) for n in param_names},
        }
        with self.client.post(
                "/command",
                json=payload,
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
    if True:
        return 
    global _raw_csv_path, _raw_csv_file, _raw_csv_writer
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
    steps = int((END_RPS - START_RPS) / STEP_RPS) + 1
    total_s = steps * STEP_DURATION_S
    print(f"[locust/ramp] Backends     : {list(BACKEND_API_KEYS.keys())}")
    print(f"[locust/ramp] HTTP plan    : {len(HTTP_PLAN)} combinations")
    print(f"[locust/ramp] MQTT plan    : {len(MQTT_PLAN)} combinations")
    print(f"[locust/ramp] Ramp         : {START_RPS} → {END_RPS} req/s "
          f"in steps of {STEP_RPS} ({steps} steps × {STEP_DURATION_S}s "
          f"= {total_s:.0f}s total)")
    print(f"[locust/ramp] Per-user RPS : {PER_USER_RPS}")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust/ramp] Test stopped.")