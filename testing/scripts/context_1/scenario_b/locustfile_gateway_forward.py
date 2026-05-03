"""
Scenarios B and C — API Gateway forwarding (no plugin logic on the request path).

Scenario B: Plugin disabled — APISIX routes directly to each device upstream.
Scenario C: Plugin enabled but does not touch the request — overhead of plugin
            presence without translation work.

HTTP devices:
  Sends POST to /device/<device_id> on the gateway. APISIX forwards to the
  device's upstream. No apikey, no translation — pure forwarding overhead.

MQTT devices:
  Direct to broker, same as scenario A. The gateway adds no value for MQTT
  without the plugin, so this gives a stable baseline for comparison with D.

Fill in HTTP_DEVICES and MQTT_* dicts at the top (same values as scenario A),
then run:
    LOCUST_FILE=locustfile_gateway_forward.py ./run_test.sh scenarioB 50 medium

Dependencies:
    pip install locust paho-mqtt
"""

import itertools
import json
import csv
import os
import random
import ssl
import threading
import time
import uuid

import gevent
import urllib3
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import paho.mqtt.client as mqtt
from locust import HttpUser, User, constant_throughput, events, task

# Suppress SSL warnings — gateway uses self-signed cert
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# | ================= User-provided device config ================= |

# HTTP devices: device_id -> (same IDs as scenario A, routes are /device/<id>)
HTTP_DEVICE_IDS: list[str] = [
    "http_device_001",
    "http_device_002",
    "http_device_003",
]

# MQTT — direct to broker, same as scenario A
MQTT_DEVICES: dict[str, str] = {
    "mqtt_device_004": "devices/mqtt_device_004/commands",
    "mqtt_device_005": "devices/mqtt_device_005/commands",
    "mqtt_device_006": "devices/mqtt_device_006/commands",
}
MQTT_RESPONSE_TOPICS: dict[str, str] = {
    "mqtt_device_004": "devices/mqtt_device_004/telemetry",
    "mqtt_device_005": "devices/mqtt_device_005/telemetry",
    "mqtt_device_006": "devices/mqtt_device_006/telemetry",
}

MQTT_BROKER_HOST        = "192.168.50.1"   # nuc1 IP — .local doesn't resolve in containers
MQTT_BROKER_PORT        = 8883
MQTT_CA_CERT_PATH       = os.environ.get("MQTT_CA_CERT_PATH", "./backend.crt")
MQTT_RESPONSE_TIMEOUT_S = 10.0

# | ================= Rate control ================= |

TARGET_RPS   = float(os.environ["TARGET_RPS"])
NUM_USERS    = int(os.environ["NUM_USERS"])
PER_USER_RPS = TARGET_RPS / NUM_USERS

HTTP_WEIGHT = max(len(HTTP_DEVICE_IDS), 1)
MQTT_WEIGHT = max(len(MQTT_DEVICES), 1)

# | ================= Medium payload ================= |

def build_medium_payload(device_id: str, command: str, correlation_id: str) -> dict:
    return {
        "type":          command,
        "correlationId": correlation_id,
        "responseTopic": f"devices/{device_id}/telemetry",
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
        "metadata": {
            "source":   "perf_test_gateway_forward",
            "priority": random.choice(["low", "medium", "high"]),
        },
        "assetIdentifiers": [f"asset_{random.randint(1, 100)}"],
    }

# | ================= HTTP user ================= |

_http_iter = itertools.cycle(HTTP_DEVICE_IDS)
_http_lock = threading.Lock()

def next_http_device() -> str:
    with _http_lock:
        return next(_http_iter)


class HttpGatewayUser(HttpUser):
    """
    Sends POST requests to /device/<device_id> on the gateway.
    APISIX forwards to the device upstream — no plugin logic involved.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = HTTP_WEIGHT

    @task
    def post_via_gateway(self):
        device_id      = next_http_device()
        correlation_id = str(uuid.uuid4())
        payload        = build_medium_payload(device_id, "setBatteryOperation", correlation_id)

        with self.client.post(
                f"/device/{device_id}",
                json=payload,
                headers={"Content-Type": "application/json"},
                name="gateway/http",
                verify=False,
                catch_response=True) as resp:
            if resp.status_code not in (200, 201, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")

# | ================= MQTT user (direct, same as scenario A) ================= |

_mqtt_iter = itertools.cycle(list(MQTT_DEVICES.items()))
_mqtt_lock = threading.Lock()

def next_mqtt_device() -> tuple[str, str]:
    with _mqtt_lock:
        return next(_mqtt_iter)


class MqttDirectUser(User):
    """
    Direct MQTT to broker — unchanged from scenario A.
    Gateway adds no MQTT value without the plugin.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = MQTT_WEIGHT

    def on_start(self):
        self._pending: dict[str, dict] = {}
        self._pending_lock = threading.Lock()
        self._connected    = False

        # Unique response topic per user — each ack is routed only to the
        # user that sent the command, eliminating fan-out overhead
        self._user_id        = str(uuid.uuid4())[:8]
        self._response_topic = f"locust/responses/{self._user_id}"

        self._client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"locust_gw_{self._user_id}",
            protocol=mqtt.MQTTv311)
        self._client.tls_set(ca_certs=MQTT_CA_CERT_PATH, tls_version=ssl.PROTOCOL_TLS_CLIENT)
        self._client.on_connect = self._on_connect
        self._client.on_message = self._on_message
        self._client.connect(MQTT_BROKER_HOST, MQTT_BROKER_PORT, keepalive=30)
        self._client.loop_start()

        deadline = time.perf_counter() + 15
        while not self._connected and time.perf_counter() < deadline:
            gevent.sleep(0.05)
        if not self._connected:
            raise Exception(f"MQTT connection timeout for user {id(self)}")

    def on_stop(self):
        self._client.loop_stop()
        self._client.disconnect()

    def _on_connect(self, client, userdata, connect_flags, reason_code, properties):
        if reason_code != 0:
            print(f"[locust/mqtt] Connection failed: {reason_code}")
            return
        client.subscribe(self._response_topic, qos=0)
        self._connected = True

    def _on_message(self, client, userdata, msg):
        try:
            data           = json.loads(msg.payload.decode())
            correlation_id = data.get("correlationId")
            if not correlation_id:
                return
            with self._pending_lock:
                entry = self._pending.pop(correlation_id, None)
            if entry:
                entry["elapsed_ms"]      = (time.perf_counter_ns() - entry["start_ns"]) / 1_000_000
                entry["response_length"] = len(msg.payload)
                entry["done"]            = True
        except Exception:
            pass

    @task
    def publish_to_device(self):
        device_id, command_topic = next_mqtt_device()
        correlation_id = str(uuid.uuid4())
        payload = build_medium_payload(device_id, "setPower", correlation_id)
        payload["responseTopic"] = self._response_topic
        payload_bytes = json.dumps(payload).encode()

        entry = {"start_ns": time.perf_counter_ns(), "elapsed_ms": None,
                 "response_length": 0, "done": False}
        with self._pending_lock:
            self._pending[correlation_id] = entry

        self._client.publish(command_topic, payload_bytes, qos=1)

        deadline = time.perf_counter() + MQTT_RESPONSE_TIMEOUT_S
        while time.perf_counter() < deadline:
            gevent.sleep(0.002)
            if entry["done"]:
                break

        received   = entry["done"]
        elapsed_ms = entry["elapsed_ms"] if received else MQTT_RESPONSE_TIMEOUT_S * 1000
        if not received:
            with self._pending_lock:
                self._pending.pop(correlation_id, None)

        events.request.fire(
            request_type="MQTT",
            name="gateway/mqtt",
            response_time=elapsed_ms,
            response_length=entry["response_length"],
            exception=None if received else Exception("MQTT response timeout"),
        )


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
    if not HTTP_DEVICE_IDS:
        errors.append("HTTP_DEVICE_IDS is empty")
    if not MQTT_DEVICES:
        errors.append("MQTT_DEVICES is empty")
    if MQTT_DEVICES and not os.path.exists(MQTT_CA_CERT_PATH):
        errors.append(f"MQTT_CA_CERT_PATH not found: {MQTT_CA_CERT_PATH}")
    if errors:
        raise RuntimeError("Config errors:\n  " + "\n  ".join(errors))

    print(f"[locust/gw-forward] HTTP devices : {HTTP_DEVICE_IDS}")
    print(f"[locust/gw-forward] HTTP route   : GET /device/<id> via {environment.host}")
    print(f"[locust/gw-forward] MQTT devices : {list(MQTT_DEVICES.keys())} (direct)")
    print(f"[locust/gw-forward] Target {TARGET_RPS} req/s via {NUM_USERS} users")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust/gw-forward] Test stopped.")