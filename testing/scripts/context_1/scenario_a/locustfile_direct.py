"""
Scenario A — Direct device communication (no API gateway).

HTTP devices:
  POST directly to the device endpoint; latency = time to HTTP response.

MQTT devices:
  Publishes a message with a correlationId in the payload (QoS 1).
  The Node.js device echoes the correlationId back on the response topic.
  Latency = time from publish to receiving the matching response — same
  measurement as MqttDeviceAdapter uses in production.

  Mosquitto runs on nuc1 (backend NUC). Locust connects via TLS using
  backend.crt as the CA cert. Set MQTT_CA_CERT_PATH if the cert is not
  at the default location.

Fill in HTTP_DEVICES, MQTT_DEVICES, and MQTT_RESPONSE_TOPICS at the top,
then run:
    LOCUST_FILE=locustfile_direct.py ./run_test.sh scenarioA 50 medium

The Locust CSV produces separate rows for "direct/http" and "direct/mqtt",
which run_test.sh extracts as separate columns in the master CSV.

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

import urllib3
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import gevent
import paho.mqtt.client as mqtt
from locust import HttpUser, User, constant_throughput, events, task

# Suppress SSL warnings from direct HTTPS to self-signed device certs
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# | ================= User-provided device config ================= |

# HTTP devices: device_id -> full URL to POST to
HTTP_DEVICES: dict[str, str] = {
    "http_device_001": "https://192.168.50.8:8443/v2/schedule/",
    "http_device_002": "https://192.168.50.8:8444/v2/schedule/",
    "http_device_003": "https://192.168.50.8:8445/v2/schedule/",
}

# MQTT devices: device_id -> command topic to publish on
MQTT_DEVICES: dict[str, str] = {
    "mqtt_device_004": "devices/mqtt_device_004/commands",
    "mqtt_device_005": "devices/mqtt_device_005/commands",
    "mqtt_device_006": "devices/mqtt_device_006/commands",
}

# Response topic per device — the Node.js device echoes correlationId here
MQTT_RESPONSE_TOPICS: dict[str, str] = {
    "mqtt_device_004": "devices/mqtt_device_004/telemetry",
    "mqtt_device_005": "devices/mqtt_device_005/telemetry",
    "mqtt_device_006": "devices/mqtt_device_006/telemetry",
}

# | ================= MQTT broker config ================= |

# Mosquitto runs on the backend NUC for scenario A
MQTT_BROKER_HOST = "192.168.50.1"
MQTT_BROKER_PORT = 8883

# CA cert for verifying the broker's self-signed TLS certificate.
# backend.crt must be present on the machine running Locust.
MQTT_CA_CERT_PATH = os.environ.get("MQTT_CA_CERT_PATH", "./backend.crt")

# Max wait for a correlationId echo before recording a timeout failure
MQTT_RESPONSE_TIMEOUT_S = 10.0

# | ================= Rate control ================= |

TARGET_RPS   = float(os.environ["TARGET_RPS"])
NUM_USERS    = int(os.environ["NUM_USERS"])
PER_USER_RPS = TARGET_RPS / NUM_USERS

HTTP_WEIGHT = max(len(HTTP_DEVICES), 1)
MQTT_WEIGHT = max(len(MQTT_DEVICES), 1)

# | ================= Medium payload ================= |

def build_medium_payload(device_id: str, command: str, correlation_id: str) -> dict:
    """
    Builds a realistic medium-sized payload (~10 fields).
    correlationId is embedded so the device can echo it back in the response.
    Actual field values don't matter for scenario A — we measure transport only.
    """
    return {
        "type":          command,
        "correlationId": correlation_id,
        # responseTopic is required by the Node.js device to know where to echo the ack
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
            "source":   "perf_test_direct",
            "priority": random.choice(["low", "medium", "high"]),
        },
        "assetIdentifiers": [f"asset_{random.randint(1, 100)}"],
    }

# | ================= HTTP user ================= |

# Shared counter for assigning devices to users round-robin at spawn time
_http_device_list = list(HTTP_DEVICES.items())
_http_assign_idx  = 0
_http_assign_lock = threading.Lock()

def assign_http_device() -> tuple[str, str]:
    """Assigns the next device to a user at spawn time. Each user keeps the same device."""
    global _http_assign_idx
    with _http_assign_lock:
        item = _http_device_list[_http_assign_idx % len(_http_device_list)]
        _http_assign_idx += 1
        return item


class HttpDeviceUser(HttpUser):
    """
    Sends POST requests directly to HTTP device endpoints.
    Each user is pinned to one device at spawn time so the TLS connection
    stays warm across requests — avoids per-request TLS renegotiation caused
    by round-robining between different hosts within the same session.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = HTTP_WEIGHT

    @task
    def post_to_device(self):
        device_id, url = assign_http_device()
        correlation_id = str(uuid.uuid4())
        payload = build_medium_payload(device_id, "setBatteryOperation", correlation_id)

        with self.client.post(
                url,
                json=payload,
                headers={"Content-Type": "application/json"},
                name="direct/http",
                verify=False,       # self-signed cert on device NUC
                catch_response=True) as resp:
            if resp.status_code not in (200, 201, 202):
                resp.failure(f"status={resp.status_code} body={resp.text[:200]}")

# | ================= MQTT user ================= |

_mqtt_iter = itertools.cycle(list(MQTT_DEVICES.items()))
_mqtt_lock = threading.Lock()

def next_mqtt_device() -> tuple[str, str]:
    with _mqtt_lock:
        return next(_mqtt_iter)


class MqttDeviceUser(User):
    """
    Publishes messages directly to MQTT device command topics on nuc1's broker.

    Each message carries a correlationId. The Node.js device echoes it back
    on the response topic. Latency = time from publish to receiving the
    matching response, i.e. the full device round-trip.

    Each virtual user maintains its own persistent TLS connection to the broker.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = MQTT_WEIGHT

    def on_start(self):
        self._pending: dict[str, dict] = {}
        self._pending_lock = threading.Lock()
        self._connected = False

        # Unique response topic per user — eliminates fan-out overhead.
        # With shared device topics, every ack is delivered to all 50 users;
        # each must parse and discard 49 out of 50 messages. A personal topic
        # means each ack is routed only to the one user that sent the command.
        self._user_id        = str(uuid.uuid4())[:8]
        self._response_topic = f"locust/responses/{self._user_id}"

        self._client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"locust_direct_{self._user_id}",
            protocol=mqtt.MQTTv311)

        self._client.tls_set(
            ca_certs=MQTT_CA_CERT_PATH,
            tls_version=ssl.PROTOCOL_TLS_CLIENT)

        self._client.on_connect = self._on_connect
        self._client.on_message = self._on_message
        self._client.connect(MQTT_BROKER_HOST, MQTT_BROKER_PORT, keepalive=30)
        self._client.loop_start()

        deadline = time.perf_counter() + 15
        while not self._connected and time.perf_counter() < deadline:
            gevent.sleep(0.0005)
        if not self._connected:
            raise Exception(f"MQTT connection timeout for user {id(self)}")

    def on_stop(self):
        self._client.loop_stop()
        self._client.disconnect()

    def _on_connect(self, client, userdata, connect_flags, reason_code, properties):
        if reason_code != 0:
            print(f"[locust/mqtt] Connection failed reason_code={reason_code}")
            return
        # Subscribe only to this user's personal response topic
        client.subscribe(self._response_topic, qos=0)
        self._connected = True

    def _on_message(self, client, userdata, msg):
        """
        Called by the paho network thread on incoming response topic messages.
        Matches the correlationId and unblocks the waiting task.
        """
        try:
            data = json.loads(msg.payload.decode())
            correlation_id = data.get("correlationId")
            if not correlation_id:
                return

            with self._pending_lock:
                entry = self._pending.pop(correlation_id, None)

            if entry:
                entry["elapsed_ms"]     = (time.perf_counter_ns() - entry["start_ns"]) / 1_000_000
                entry["response_length"] = len(msg.payload)
                entry["done"]           = True

        except Exception:
            pass

    @task
    def publish_to_device(self):
        device_id, command_topic = next_mqtt_device()
        correlation_id = str(uuid.uuid4())
        # Override responseTopic with this user's personal topic
        payload = build_medium_payload(device_id, "setPower", correlation_id)
        payload["responseTopic"] = self._response_topic
        payload_bytes = json.dumps(payload).encode()

        entry = {"start_ns": time.perf_counter_ns(), "elapsed_ms": None,
                 "response_length": 0, "done": False}

        # Register before publishing to avoid a race where the response
        # arrives before we've added the entry to pending
        with self._pending_lock:
            self._pending[correlation_id] = entry

        self._client.publish(command_topic, payload_bytes, qos=1)

        # Poll with gevent.sleep() — compatible with both gevent greenlets and
        # the real OS thread that paho uses for loop_start()
        deadline = time.perf_counter() + MQTT_RESPONSE_TIMEOUT_S
        while time.perf_counter() < deadline:
            gevent.sleep(0.002)   # yield to gevent loop, check every 2ms
            if entry["done"]:
                break

        received   = entry["done"]
        elapsed_ms = entry["elapsed_ms"] if received else MQTT_RESPONSE_TIMEOUT_S * 1000

        if not received:
            with self._pending_lock:
                self._pending.pop(correlation_id, None)

        events.request.fire(
            request_type="MQTT",
            name="direct/mqtt",
            response_time=elapsed_ms,
            # Use the actual response payload size (the ack from the device),
            # consistent with how Locust measures HTTP response body size
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
    if not HTTP_DEVICES:
        errors.append("HTTP_DEVICES is empty")
    if not MQTT_DEVICES:
        errors.append("MQTT_DEVICES is empty")
    if MQTT_DEVICES and not MQTT_RESPONSE_TOPICS:
        errors.append("MQTT_RESPONSE_TOPICS is empty but MQTT_DEVICES is set")
    if set(MQTT_DEVICES.keys()) != set(MQTT_RESPONSE_TOPICS.keys()):
        errors.append("MQTT_DEVICES and MQTT_RESPONSE_TOPICS must have the same device IDs")
    if MQTT_DEVICES and not os.path.exists(MQTT_CA_CERT_PATH):
        errors.append(f"MQTT_CA_CERT_PATH not found: {MQTT_CA_CERT_PATH}")
    if errors:
        raise RuntimeError("Config errors:\n  " + "\n  ".join(errors))

    print(f"[locust/direct] HTTP devices : {list(HTTP_DEVICES.keys())}")
    print(f"[locust/direct] MQTT devices : {list(MQTT_DEVICES.keys())}")
    print(f"[locust/direct] MQTT broker  : {MQTT_BROKER_HOST}:{MQTT_BROKER_PORT} (TLS)")
    print(f"[locust/direct] CA cert      : {MQTT_CA_CERT_PATH}")
    print(f"[locust/direct] Target {TARGET_RPS} req/s via {NUM_USERS} users "
          f"({PER_USER_RPS:.3f} req/s each)")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust/direct] Test stopped.")