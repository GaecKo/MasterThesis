"""
Scenario A — Direct device communication (no API gateway).

Measures raw communication latency and throughput by sending payloads
directly to devices, bypassing the gateway entirely.

HTTP devices:
  POST directly to the device endpoint; latency = time to HTTP response.

MQTT devices:
  Publishes a message with a correlationId in the payload (QoS 1).
  The Node.js device echoes the correlationId back on the response topic.
  Latency = time from publish to receiving the matching response — same
  measurement as MqttDeviceAdapter uses in production.

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
import os
import random
import threading
import time
import uuid

import paho.mqtt.client as mqtt
from locust import HttpUser, User, constant_throughput, events, task

# | ================= User-provided device config ================= |

# HTTP devices: device_id -> full URL to POST to
HTTP_DEVICES: dict[str, str] = {
    # "http_device_001": "https://nuc8-pc.local:8443/v2/schedule/",
    # "http_device_002": "https://nuc8-pc.local:8444/v2/schedule/",
    # "http_device_003": "https://nuc8-pc.local:8445/v2/schedule/",
}

# MQTT devices: device_id -> command topic to publish on
MQTT_DEVICES: dict[str, str] = {
    # "mqtt_device_001": "devices/mqtt_device_001/commands",
    # "mqtt_device_002": "devices/mqtt_device_002/commands",
    # "mqtt_device_003": "devices/mqtt_device_003/commands",
}

# Response topic per device — the Node.js device echoes correlationId here
MQTT_RESPONSE_TOPICS: dict[str, str] = {
    # "mqtt_device_001": "devices/mqtt_device_001/telemetry",
    # "mqtt_device_002": "devices/mqtt_device_002/telemetry",
    # "mqtt_device_003": "devices/mqtt_device_003/telemetry",
}

# MQTT broker (Mosquitto on the gateway NUC)
MQTT_BROKER_HOST = "nuc4-pc.local"
MQTT_BROKER_PORT = 1883

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

_http_iter = itertools.cycle(list(HTTP_DEVICES.items()))
_http_lock = threading.Lock()

def next_http_device() -> tuple[str, str]:
    with _http_lock:
        return next(_http_iter)


class HttpDeviceUser(HttpUser):
    """Sends POST requests directly to HTTP device endpoints."""
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = HTTP_WEIGHT

    @task
    def post_to_device(self):
        device_id, url = next_http_device()
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
    Publishes messages directly to MQTT device command topics.

    Each message carries a correlationId. The Node.js device echoes it back
    on the response topic. Latency = time from publish to receiving the
    matching response, i.e. the full device round-trip.

    Each virtual user maintains its own persistent broker connection and
    subscription to all response topics.
    """
    wait_time = constant_throughput(PER_USER_RPS)
    weight    = MQTT_WEIGHT

    def on_start(self):
        # pending maps correlationId -> (start_ns, threading.Event)
        # so the paho network thread can unblock the waiting task
        self._pending: dict[str, tuple[int, threading.Event]] = {}
        self._pending_lock = threading.Lock()

        self._client = mqtt.Client(
            client_id=f"locust_direct_{id(self)}",
            protocol=mqtt.MQTTv311)
        self._client.on_connect = self._on_connect
        self._client.on_message = self._on_message
        self._client.connect(MQTT_BROKER_HOST, MQTT_BROKER_PORT, keepalive=30)
        self._client.loop_start()

    def on_stop(self):
        self._client.loop_stop()
        self._client.disconnect()

    def _on_connect(self, client, userdata, flags, rc):
        # Subscribe to every response topic so this user can receive acks
        # for whichever device it happens to send to
        for response_topic in MQTT_RESPONSE_TOPICS.values():
            client.subscribe(response_topic, qos=1)

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
                start_ns, done_event = entry
                # Attach elapsed_ms to the event object so the task can read it
                done_event.elapsed_ms = (time.perf_counter_ns() - start_ns) / 1_000_000
                done_event.set()

        except Exception:
            pass

    @task
    def publish_to_device(self):
        device_id, command_topic = next_mqtt_device()
        correlation_id = str(uuid.uuid4())
        payload_bytes  = json.dumps(
            build_medium_payload(device_id, "setPower", correlation_id)
        ).encode()

        done_event = threading.Event()
        done_event.elapsed_ms = None

        # Register before publishing to avoid a race where the response
        # arrives before we've added the entry to pending
        with self._pending_lock:
            self._pending[correlation_id] = (time.perf_counter_ns(), done_event)

        self._client.publish(command_topic, payload_bytes, qos=1)

        received   = done_event.wait(timeout=MQTT_RESPONSE_TIMEOUT_S)
        elapsed_ms = done_event.elapsed_ms if received else MQTT_RESPONSE_TIMEOUT_S * 1000

        if not received:
            # Timed out — clean up so the dict doesn't grow unboundedly
            with self._pending_lock:
                self._pending.pop(correlation_id, None)

        events.request.fire(
            request_type="MQTT",
            name="direct/mqtt",
            response_time=elapsed_ms,
            response_length=len(payload_bytes),
            exception=None if received else Exception("MQTT response timeout"),
        )

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
    if errors:
        raise RuntimeError("Config errors:\n  " + "\n  ".join(errors))

    print(f"[locust/direct] HTTP devices: {list(HTTP_DEVICES.keys())}")
    print(f"[locust/direct] MQTT devices: {list(MQTT_DEVICES.keys())}")
    print(f"[locust/direct] MQTT measures full correlationId round-trip")
    print(f"[locust/direct] Target {TARGET_RPS} req/s via {NUM_USERS} users "
          f"({PER_USER_RPS:.3f} req/s each)")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("[locust/direct] Test stopped.")