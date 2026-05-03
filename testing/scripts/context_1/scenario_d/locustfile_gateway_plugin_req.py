"""
Scenario D — API Gateway with full plugin, no AuthN/Z, no translation.

The plugin receives the request, finds the adapter via gatewayDeviceId,
and fires the pre-built payload directly to the device. No translation
occurs because params is empty — the body itself IS the device payload.

HTTP devices:  POST /command → plugin → device adapter → device
MQTT devices:  POST /command → plugin → MQTT adapter → broker → device
               (MQTT response matched via correlationId, same as scenario A)

No apikey header required — /command route has AuthFilter disabled.

Fill in DEVICE_COMMANDS at the top to match what was uploaded via
generate_configs.py, then run:
    LOCUST_FILE=locustfile_gateway_plugin_req.py ./run_test.sh scenarioD 100 medium

Dependencies:
    pip install locust paho-mqtt
"""

import itertools
import json
import csv
import os
import random
import threading
import time
import uuid

import urllib3
from locust import HttpUser, User, constant_throughput, events, task

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# | ================= User-provided device config ================= |

# Map each device ID to the list of commands it supports.
# Must match what was uploaded via generate_configs.py.
DEVICE_COMMANDS: dict[str, list[str]] = {
    "http_device_001": ["setBatteryOperation", "setChargeTarget", "setMaxCapacity", "setMaxCapacity", "setMaxCapacity"],
    "http_device_002": ["setBatteryOperation", "setChargeTarget", "setMaxCapacity", "setMaxCapacity", "setMaxCapacity"],
    "http_device_003": ["setBatteryOperation", "setChargeTarget", "setMaxCapacity", "setMaxCapacity", "setMaxCapacity"],
    "mqtt_device_004": ["setPower", "setMode", "setChargeTarget", "setMaxCapacity", "setMaxCapacity", "setMaxCapacity"],
    "mqtt_device_005": ["setPower", "setMode", "setChargeTarget", "setMaxCapacity", "setMaxCapacity", "setMaxCapacity"],
    "mqtt_device_006": ["setPower", "setMode", "setChargeTarget", "setMaxCapacity", "setMaxCapacity", "setMaxCapacity"],
}

# MQTT broker — for correlationId round-trip timing

# | ================= Rate control ================= |

TARGET_RPS   = float(os.environ["TARGET_RPS"])
NUM_USERS    = int(os.environ["NUM_USERS"])
PER_USER_RPS = TARGET_RPS / NUM_USERS

HTTP_DEVICES = {k: v for k, v in DEVICE_COMMANDS.items() if k.startswith("http_")}
MQTT_DEVICES = {k: v for k, v in DEVICE_COMMANDS.items() if k.startswith("mqtt_")}

HTTP_WEIGHT = max(len(HTTP_DEVICES), 1)
MQTT_WEIGHT = max(len(MQTT_DEVICES), 1)

# | ================= Request plan ================= |

# Round-robin over (device_id, command) pairs per protocol
# Note: itertools.cycle() must NOT be wrapped in list() — it is infinite
_http_iter = itertools.cycle(
    [(dev, cmd) for dev, cmds in HTTP_DEVICES.items() for cmd in cmds])
_http_lock = threading.Lock()
_mqtt_iter = itertools.cycle(
    [(dev, cmd) for dev, cmds in MQTT_DEVICES.items() for cmd in cmds])
_mqtt_lock = threading.Lock()

def next_http() -> tuple[str, str]:
    with _http_lock:
        return next(_http_iter)

def next_mqtt() -> tuple[str, str]:
    with _mqtt_lock:
        return next(_mqtt_iter)

# | ================= Payload builder ================= |

def build_payload(device_id: str, command: str, correlation_id: str) -> dict:
    """
    Builds the full device-ready payload and wraps it with the gateway
    routing fields (gatewayDeviceId, command, params).

    params is empty — the plugin forwards the body as-is without translation.
    The payload shape matches what the device expects directly.
    """
    is_http = device_id.startswith("http_")

    if is_http:
        device_payload = {
            "schedule": [{
                "type":      command,
                "operation": {
                    "dispatchPower": {
                        "activePower":   random.randint(1000, 50000),
                        "reactivePower": random.randint(0, 10000),
                        "apparentPower": random.randint(1000, 50000),
                        "frequency":     round(random.uniform(49.5, 50.5), 2),
                        "voltage":       random.randint(220, 240),
                    },
                    "deliverFCR": {
                        "maxRate": round(random.uniform(0.5, 10.0), 2),
                        "minRate": round(random.uniform(0.1, 0.5), 2),
                    },
                },
                "startAt": "2024-01-01T00:00:00Z",
                "endAt":   "2024-01-02T00:00:00Z",
            }],
            "assetIdentifiers": [f"asset_{random.randint(1, 100)}"],
            "metadata": {
                "source":        "perf_test_plugin",
                "correlationId": correlation_id,
                "priority":      random.choice(["low", "medium", "high"]),
            },
        }
    else:
        # MQTT device payload — plugin forwards this to the broker
        device_payload = {
            "type":          command,
            "correlationId": correlation_id,
            "responseTopic": f"devices/{device_id}/telemetry",
            "operation": {
                "dispatchPower": {
                    "activePower":   random.randint(1000, 50000),
                    "reactivePower": random.randint(0, 10000),
                    "frequency":     round(random.uniform(49.5, 50.5), 2),
                },
                "deliverFCR": {
                    "maxRate": round(random.uniform(0.5, 10.0), 2),
                    "minRate": round(random.uniform(0.1, 0.5), 2),
                },
            },
            "startAt": "2024-01-01T00:00:00Z",
            "endAt":   "2024-01-02T00:00:00Z",
        }

    return {
        "gatewayDeviceId": device_id,
        "command":         command,
        "params":          {},       # empty — no translation, body is already device-ready
        **device_payload,
    }

# | ================= HTTP user ================= |

class HttpPluginUser(HttpUser):
    """
    Sends POST /command to the gateway with a pre-built device payload.
    No apikey header — AuthFilter is disabled on this route.
    The plugin routes based on gatewayDeviceId and fires the body to the device.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = HTTP_WEIGHT

    @task
    def send_command(self):
        device_id, command = next_http()
        correlation_id     = str(uuid.uuid4())
        payload            = build_payload(device_id, command, correlation_id)

        with self.client.post(
                "/command",
                json=payload,
                headers={"Content-Type": "application/json"},
                name="plugin/http",
                verify=False,
                catch_response=True) as resp:
            if resp.status_code not in (200, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")

# | ================= MQTT user ================= |

class MqttPluginUser(HttpUser):
    """
    Sends POST /command to the gateway for MQTT devices.

    In scenario D the plugin handles all MQTT logic internally — it publishes
    to the broker and waits for the device's correlationId ack before returning
    the HTTP response. Locust therefore just sends a plain HTTP POST and waits
    for the response, which already includes the full end-to-end round trip:
        Locust → POST /command → plugin → broker → device → ack → plugin → HTTP response → Locust
    No separate MQTT client needed here.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = MQTT_WEIGHT

    @task
    def send_command(self):
        device_id, command = next_mqtt()
        correlation_id     = str(uuid.uuid4())
        payload            = build_payload(device_id, command, correlation_id)

        with self.client.post(
                "/command",
                json=payload,
                headers={"Content-Type": "application/json"},
                name="plugin/mqtt",
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
    # Only record raw latencies during the capture phase, not warmup.
    # run_test.sh sets CAPTURE_PHASE=true only for the capture invocation.
    if os.environ.get("CAPTURE_PHASE", "false").lower() != "true":
        print("[locust] Warmup phase — raw latency recording disabled")
        return
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
    errors = []
    if not HTTP_DEVICES:
        errors.append("No HTTP devices in DEVICE_COMMANDS")
    if not MQTT_DEVICES:
        errors.append("No MQTT devices in DEVICE_COMMANDS")
    if errors:
        raise RuntimeError("Config errors:\n  " + "\n  ".join(errors))

    print(f"[locust/plugin] HTTP devices : {list(HTTP_DEVICES.keys())}")
    print(f"[locust/plugin] MQTT devices : {list(MQTT_DEVICES.keys())}")
    print(f"[locust/plugin] Route        : POST /command (no auth)")
    print(f"[locust/plugin] Target {TARGET_RPS} req/s via {NUM_USERS} users "
          f"({PER_USER_RPS:.3f} req/s each)")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust/plugin] Test stopped.")