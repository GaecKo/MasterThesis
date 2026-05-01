"""
Scenario E — Full stack: AuthN/Z + translation + plugin does request.

The gateway receives a command request with an apikey header. The AuthFilter
authenticates the backend and checks authorization. The TranslationFilter
maps params onto the device payload template and sends the translated request
to the device.

Request shape:
    POST /command
    Headers: apikey: <backend_api_key>
    Body: {
        "gatewayDeviceId": "<device_id>",
        "command":         "<command_name>",
        "params": {
            "<param_name>": <realistic_value>,
            ...
        }
    }

Device/command/param definitions are read from generated_configs/_summary.json.
Backend API keys are hardcoded at the top — fill them in before running.

Run:
    LOCUST_FILE=locustfile_gateway_full.py ./run_test.sh scenarioE 100 medium

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
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from locust import HttpUser, constant_throughput, events, task

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# | ================= User-provided backend API keys ================= |

# Map each backend gateway ID to its plain-text API key.
# Map each backend ID to its plain-text API key.
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
        f"Summary not found at {SUMMARY_PATH} — run generate_configs.py first "
        f"or set CONFIGS_SUMMARY env var.")

with SUMMARY_PATH.open() as f:
    SUMMARY = json.load(f)

DEVICES = SUMMARY["devices"]   # device_id -> { adapter, commands: {name -> [param_names]} }

if not BACKEND_API_KEYS:
    raise RuntimeError("BACKEND_API_KEYS is empty — fill in the dict at the top of this file.")

# Build flat round-robin plan: (backend_id, api_key, device_id, command_name, param_names)
# Separate plans for HTTP and MQTT so they can be weighted independently.
HTTP_PLAN: list[tuple[str, str, str, str, list[str]]] = []
MQTT_PLAN: list[tuple[str, str, str, str, list[str]]] = []

for backend_id, api_key in BACKEND_API_KEYS.items():
    for device_id, device_info in DEVICES.items():
        plan = HTTP_PLAN if device_info["adapter"] == "http" else MQTT_PLAN
        for command_name, param_names in device_info["commands"].items():
            plan.append((backend_id, api_key, device_id, command_name, param_names))

if not HTTP_PLAN and not MQTT_PLAN:
    raise RuntimeError("Request plan is empty — check _summary.json.")

print(f"[locust/full] HTTP plan: {len(HTTP_PLAN)} combinations")
print(f"[locust/full] MQTT plan: {len(MQTT_PLAN)} combinations")

_http_iter = itertools.cycle(HTTP_PLAN)
_mqtt_iter = itertools.cycle(MQTT_PLAN)
_http_lock = threading.Lock()
_mqtt_lock = threading.Lock()

def next_http():
    with _http_lock:
        return next(_http_iter)

def next_mqtt():
    with _mqtt_lock:
        return next(_mqtt_iter)

# | ================= Rate control ================= |

TARGET_RPS   = float(os.environ["TARGET_RPS"])
NUM_USERS    = int(os.environ["NUM_USERS"])
PER_USER_RPS = TARGET_RPS / NUM_USERS

# Weight by number of devices, not plan size — otherwise devices with more
# commands get disproportionately more traffic than devices with fewer commands.
HTTP_WEIGHT = max(sum(1 for d in DEVICES.values() if d["adapter"] == "http"), 1)
MQTT_WEIGHT = max(sum(1 for d in DEVICES.values() if d["adapter"] == "mqtt"), 1)

# | ================= Param value generator ================= |

def sample_param_value(param_name: str):
    """Generates a realistic value for a param based on its name."""
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
        return str(uuid.uuid4())
    if param_name == "duration":
        return random.randint(60, 3600)
    # String fallback for any unmapped param
    return f"val_{random.randint(0, 1000)}"

# | ================= HTTP user ================= |

class HttpFullUser(HttpUser):
    """
    Sends authenticated, translated commands to HTTP devices via the gateway.
    The plugin authenticates the backend, checks authorization, maps params
    onto the device payload template, and forwards the result to the device.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = HTTP_WEIGHT

    def on_start(self):
        # Persistent TLS connection pool — reuses TLS sessions across requests
        # so per-request TLS overhead doesn't inflate latency measurements.
        adapter = HTTPAdapter(
            pool_connections=1,
            pool_maxsize=1,
            max_retries=Retry(total=0),
        )
        self.client.mount("https://", adapter)
        self.client.mount("http://",  adapter)

    @task
    def send_command(self):
        backend_id, api_key, device_id, command_name, param_names = next_http()

        params = {name: sample_param_value(name) for name in param_names}

        payload = {
            "gatewayDeviceId": device_id,
            "command":         command_name,
            "params":          params,
        }

        with self.client.post(
                "/command",
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "apikey":       api_key,
                },
                name="full/http",
                verify=False,
                catch_response=True) as resp:
            if resp.status_code not in (200, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")

# | ================= MQTT user ================= |

class MqttFullUser(HttpUser):
    """
    Sends authenticated, translated commands to MQTT devices via the gateway.
    The plugin handles the MQTT adapter internally — Locust measures the full
    round trip: HTTP POST → plugin → broker → device ack → HTTP response.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = MQTT_WEIGHT

    def on_start(self):
        # Persistent TLS connection pool — reuses TLS sessions across requests
        # so per-request TLS overhead doesn't inflate latency measurements.
        adapter = HTTPAdapter(
            pool_connections=1,
            pool_maxsize=1,
            max_retries=Retry(total=0),
        )
        self.client.mount("https://", adapter)
        self.client.mount("http://",  adapter)

    @task
    def send_command(self):
        backend_id, api_key, device_id, command_name, param_names = next_mqtt()

        params = {name: sample_param_value(name) for name in param_names}

        payload = {
            "gatewayDeviceId": device_id,
            "command":         command_name,
            "params":          params,
        }

        with self.client.post(
                "/command",
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "apikey":       api_key,
                },
                name="full/mqtt",
                verify=False,
                catch_response=True) as resp:
            if resp.status_code not in (200, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")

# | ================= Raw latency listener ================= |

# Writes one row per request to raw_latencies.csv for box plot analysis.
# Columns: timestamp_ms, protocol, name, response_time_ms, success
_raw_csv_path   = None
_raw_csv_file   = None
_raw_csv_writer = None
_raw_csv_lock   = threading.Lock()

@events.init.add_listener
def init_raw_csv(environment, **kwargs):
    global _raw_csv_path, _raw_csv_file, _raw_csv_writer
    results_dir = os.environ.get("RESULTS_DIR", ".")
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

# | ================= Startup validation ================= |

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    print(f"[locust/full] Backends      : {list(BACKEND_API_KEYS.keys())}")
    print(f"[locust/full] HTTP devices  : "
          f"{[d for d,i in DEVICES.items() if i['adapter']=='http']}")
    print(f"[locust/full] MQTT devices  : "
          f"{[d for d,i in DEVICES.items() if i['adapter']=='mqtt']}")
    print(f"[locust/full] Target {TARGET_RPS} req/s via {NUM_USERS} users "
          f"({PER_USER_RPS:.3f} req/s each)")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust/full] Test stopped.")