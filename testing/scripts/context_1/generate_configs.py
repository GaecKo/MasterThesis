#!/usr/bin/env python3
"""
Generates device translation configurations for HTTP and MQTT devices,
and uploads them to the APISIX gateway via /onboarding/translation.

Usage:
    python3 generate_configs.py [--size medium|large]
"""

import argparse
import json
import random
import sys
import time
from pathlib import Path

import jwt
import requests

# | ================= User-provided input ================= |

# Map each device ID to the list of commands it supports.
# HTTP devices must start with "http_device_", MQTT with "mqtt_device_".
DEVICE_COMMANDS: dict[str, list[str]] = {
    # "http_device_001": ["setBatteryOperation", "setChargeTarget"],
    # "mqtt_device_001": ["setPower", "setMode"],
}

# Map each backend ID to its plain-text API key.
BACKEND_API_KEYS: dict[str, str] = {
    # "backend_001": "abc123...",
}

# | ================= Configuration ================= |

GATEWAY_URL       = "http://nuc4-pc.local:9080"
ONBOARDING_PATH   = "/onboarding/translation"
JWT_SECRET        = "strong-secret-for-jwt-token-device-admin"
JWT_KEY           = "device-admin"

HTTP_DEVICE_HOST      = "nuc8-pc.local"
HTTP_DEVICE_PORT_BASE = 8443

OUTPUT_DIR = Path("./generated_configs")

SIZE_RANGES = {
    "medium": (5, 15),
    "large":  (25, 30),
}

# | ================= Field pool ================= |

# Needs to be large enough that "large" (25-30 mappings) doesn't exhaust paths
FIELD_POOL = [
    ("activePower",        "operation.dispatchPower.activePower"),
    ("reactivePower",      "operation.dispatchPower.reactivePower"),
    ("apparentPower",      "operation.dispatchPower.apparentPower"),
    ("frequency",          "operation.dispatchPower.frequency"),
    ("voltage",            "operation.dispatchPower.voltage"),
    ("current",            "operation.dispatchPower.current"),
    ("maxRate",            "operation.deliverFCR.maxRate"),
    ("minRate",            "operation.deliverFCR.minRate"),
    ("deadband",           "operation.deliverFCR.deadband"),
    ("droopSetting",       "operation.deliverFCR.droopSetting"),
    ("percentage",         "operation.chargeToState.percentage"),
    ("targetSoC",          "operation.chargeToState.targetSoC"),
    ("rampRate",           "operation.chargeToState.rampRate"),
    ("maxCurrent",         "operation.chargeToState.maxCurrent"),
    ("chargeMode",         "operation.chargeToState.chargeMode"),
    ("duration",           "duration"),
    ("priority",           "metadata.priority"),
    ("source",             "metadata.source"),
    ("reason",             "metadata.reason"),
    ("correlationId",      "metadata.correlationId"),
    ("requestedBy",        "metadata.requestedBy"),
    ("requestVersion",     "metadata.requestVersion"),
    ("assetIdentifiers",   "assetIdentifiers"),
    ("tenantId",           "context.tenantId"),
    ("siteId",             "context.siteId"),
    ("operatorId",         "context.operatorId"),
    ("environmentTag",     "context.environmentTag"),
    ("regionCode",         "context.regionCode"),
    ("timezone",           "context.timezone"),
    ("overrideExisting",   "flags.overrideExisting"),
    ("notifyOnComplete",   "flags.notifyOnComplete"),
    ("auditTrailEnabled",  "flags.auditTrailEnabled"),
    ("dryRun",             "flags.dryRun"),
    ("forceExecution",     "flags.forceExecution"),
]

# | ================= Helpers ================= |

def set_nested(d: dict, path: str, value) -> None:
    """Sets a value at a dot/bracket path, creating intermediate dicts/arrays as needed."""
    parts = []
    token = ""
    i = 0
    while i < len(path):
        c = path[i]
        if c == ".":
            if token:
                parts.append(("key", token)); token = ""
            i += 1
        elif c == "[":
            if token:
                parts.append(("key", token)); token = ""
            end = path.index("]", i)
            parts.append(("idx", int(path[i + 1:end])))
            i = end + 1
        else:
            token += c; i += 1
    if token:
        parts.append(("key", token))

    cur = d
    for n, (kind, name) in enumerate(parts):
        last = n == len(parts) - 1
        nxt_kind = parts[n + 1][0] if not last else None
        if kind == "key":
            if last:
                cur[name] = value
            else:
                if name not in cur or cur[name] is None:
                    cur[name] = [] if nxt_kind == "idx" else {}
                cur = cur[name]
        else:
            while len(cur) <= name:
                cur.append({} if (not last and nxt_kind == "key") else None)
            if last:
                cur[name] = value
            else:
                if cur[name] is None:
                    cur[name] = [] if nxt_kind == "idx" else {}
                cur = cur[name]


def build_command_payload(command_name: str, is_http: bool, size_range: tuple[int, int]) -> tuple[dict, dict]:
    """
    Builds a payload template and mappings for a command.

    @param size_range (min, max) number of mappings to include
    @return (payloadTemplate, mappings)
    """
    # startAt / endAt are always included so Locust always has timestamps
    core_keys = {"startAt", "endAt"}
    pool_without_core = [f for f in FIELD_POOL if f[0] not in core_keys]

    # For HTTP devices, nest most fields under schedule[0]; keep metadata/flags/context at top level
    if is_http:
        keep_at_top = lambda p: (p == "assetIdentifiers"
                                  or p.startswith("metadata.")
                                  or p.startswith("flags.")
                                  or p.startswith("context."))
        pool_without_core = [
            (k, p if keep_at_top(p) else f"schedule[0].{p}")
            for k, p in pool_without_core
        ]

    target_count = random.randint(*size_range)
    # Reserve 2 slots for the core keys; clamp to pool size to avoid sample errors
    sample_size = min(max(target_count - len(core_keys), 0), len(pool_without_core))
    chosen = random.sample(pool_without_core, k=sample_size)

    # Append core keys with the adapter-appropriate path
    if is_http:
        chosen += [("startAt", "schedule[0].startAt"), ("endAt", "schedule[0].endAt")]
    else:
        chosen += [("startAt", "startAt"), ("endAt", "endAt")]

    mappings = {k: p for k, p in chosen}

    # Build the envelope: HTTP wraps under schedule[0], MQTT is flat
    if is_http:
        template = {
            "schedule": [{"type": command_name, "operation": {}, "startAt": None, "endAt": None}],
            "assetIdentifiers": None,
        }
    else:
        template = {"type": command_name, "operation": {}, "startAt": None, "endAt": None}

    # Seed the template with null placeholders at every mapped path
    for _, path in mappings.items():
        set_nested(template, path, None)

    return template, mappings


def make_http_device_config(device_id: str, commands: list[str], port: int, size_range: tuple[int, int]) -> dict:
    """Builds the full translation config for an HTTP device."""
    cmds_block = {}
    for cmd in commands:
        payload, mappings = build_command_payload(cmd, is_http=True, size_range=size_range)
        cmds_block[cmd] = {
            "name":     cmd,
            "endpoint": f"https://{HTTP_DEVICE_HOST}:{port}/v2/schedule/",
            "method":   "POST",
            "timeouts": {"connect": 2, "request": 10},
            "payloadTemplate": payload,
            "mappings":        mappings,
            "cleanup": {
                "emptyObjectToNull": random.choice([True, False]),
                "removeNulls":       random.choice([True, False]),
                "removeEmpty":       random.choice([True, False]),
            },
        }
    return {
        "gatewayDeviceId": device_id,
        "adapter":         "http",
        "queuing":         {"retryIntervalSeconds": 5, "maxTimeToLiveSeconds": 40},
        "commands":        cmds_block,
    }


def make_mqtt_device_config(device_id: str, commands: list[str], size_range: tuple[int, int]) -> dict:
    """Builds the full translation config for an MQTT device."""
    cmds_block = {}
    for cmd in commands:
        payload, mappings = build_command_payload(cmd, is_http=False, size_range=size_range)
        cmds_block[cmd] = {
            "name":            cmd,
            "topic":           f"devices/{device_id}/commands",
            "responseTopic":   f"devices/{device_id}/telemetry",
            "timeouts":        {"response": 10},
            "payloadTemplate": payload,
            "mappings":        mappings,
            "cleanup": {
                "removeNulls":       random.choice([True, False]),
                "removeEmpty":       random.choice([True, False]),
                "emptyObjectToNull": random.choice([True, False]),
            },
        }
    return {
        "gatewayDeviceId": device_id,
        "adapter":         "mqtt",
        "queuing":         {"retryIntervalSeconds": 5, "maxTimeToLiveSeconds": 40},
        "connection": {
            "qos":               1,
            "keepAliveInterval": 30,
            "connectionTimeout": 10,
            "cleanSession":      True,
            "reconnectDelay":    5,
        },
        "subscriptions": [
            {"topic": f"devices/{device_id}/telemetry", "forwardToBackend": True},
            {"topic": f"devices/{device_id}/status",    "forwardToBackend": True},
        ],
        "commands": cmds_block,
    }


def build_jwt() -> str:
    """Generates a short-lived JWT for the /onboarding/translation endpoint."""
    exp = int(time.time()) + 86400
    return jwt.encode({"key": JWT_KEY, "exp": exp}, JWT_SECRET, algorithm="HS256")


def upload_config(config: dict, token: str) -> None:
    """POSTs a single translation config to the gateway. Crashes on failure."""
    resp = requests.post(
        f"{GATEWAY_URL}{ONBOARDING_PATH}",
        json=config,
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    if resp.status_code != 200:
        print(f"\n[ERROR] Failed to upload config for {config['gatewayDeviceId']}")
        print(f"Status: {resp.status_code}")
        print(f"Body:   {resp.text}")
        sys.exit(1)

# | ================= Main ================= |

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--size", choices=list(SIZE_RANGES.keys()), default="medium",
                        help="Payload size: medium (5-15 mappings) or large (25-30)")
    args = parser.parse_args()

    size_range = SIZE_RANGES[args.size]
    print(f"[INFO] Payload size: {args.size} ({size_range[0]}-{size_range[1]} mappings)")

    if not DEVICE_COMMANDS:
        print("[ERROR] DEVICE_COMMANDS is empty — edit the script and retry.")
        sys.exit(1)
    if not BACKEND_API_KEYS:
        print("[ERROR] BACKEND_API_KEYS is empty — edit the script and retry.")
        sys.exit(1)

    OUTPUT_DIR.mkdir(exist_ok=True)

    token = build_jwt()
    print(f"[INFO] Generated JWT (len={len(token)})")

    # Track port assignments so the user can start Node.js listeners on those ports
    http_device_ports: dict[str, int] = {}

    configs = {}
    http_port_offset = 0
    for device_id, commands in DEVICE_COMMANDS.items():
        if device_id.startswith("http_device_"):
            port = HTTP_DEVICE_PORT_BASE + http_port_offset
            http_port_offset += 1
            http_device_ports[device_id] = port
            config = make_http_device_config(device_id, commands, port, size_range)
            print(f"[INFO] HTTP device {device_id} -> port {port}, {len(commands)} commands")
        elif device_id.startswith("mqtt_device_"):
            config = make_mqtt_device_config(device_id, commands, size_range)
            print(f"[INFO] MQTT device {device_id}, {len(commands)} commands")
        else:
            print(f"[ERROR] Unknown device prefix for {device_id} — must start with http_device_ or mqtt_device_")
            sys.exit(1)

        configs[device_id] = config
        (OUTPUT_DIR / f"{device_id}.json").write_text(json.dumps(config, indent=2))

    print(f"\n[INFO] Uploading {len(configs)} configs to {GATEWAY_URL}{ONBOARDING_PATH}")
    for device_id, config in configs.items():
        upload_config(config, token)
        print(f"  ✓ {device_id}")

    summary = {
        "payload_size":      args.size,
        "payload_range":     list(size_range),
        "devices": {
            device_id: {
                "adapter":  config["adapter"],
                "commands": {
                    cmd_name: list(cmd_cfg["mappings"].keys())
                    for cmd_name, cmd_cfg in config["commands"].items()
                },
            }
            for device_id, config in configs.items()
        },
        "backends":          BACKEND_API_KEYS,
        "http_device_ports": http_device_ports,
    }
    (OUTPUT_DIR / "_summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\n[INFO] Summary written to {OUTPUT_DIR / '_summary.json'}")

    if http_device_ports:
        print(f"\n[INFO] HTTP device ports (start Node.js listeners on these):")
        for dev, port in http_device_ports.items():
            print(f"  {dev}: {port}")

    print("[INFO] Done.")


if __name__ == "__main__":
    main()