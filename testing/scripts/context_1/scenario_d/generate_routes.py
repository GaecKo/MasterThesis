#!/usr/bin/env python3
"""
Generates APISIX route configuration for scenarios B and C.

One route is created per HTTP device, each with its own upstream pointing
directly to that device's HTTPS port on nuc8. The Locust script then sends
to /device/<device_id> per device instead of the single /command route.

Usage:
    python3 generate_routes.py              # prints curl commands
    python3 generate_routes.py --apply      # executes the curl commands immediately
    python3 generate_routes.py --delete     # deletes all generated routes

Routes created:
    POST /device/<device_id>  →  https://192.168.50.8:<port>/v2/schedule/

The APISIX Admin API key and address can be overridden via env vars.
"""

import argparse
import json
import subprocess
import sys
import os
from pathlib import Path

# | ================= Configuration ================= |

SUMMARY_PATH    = Path(os.environ.get("SUMMARY_PATH", "./generated_configs/_summary.json"))
APISIX_ADMIN    = os.environ.get("APISIX_ADMIN", "http://localhost:9180")
ADMIN_API_KEY   = os.environ.get("ADMIN_API_KEY", "admin")  # default APISIX key

# Route IDs are deterministic so --delete can find them without state
ROUTE_ID_BASE   = 2000   # starts at 2000 to avoid clashing with existing routes

import os

# | ================= Helpers ================= |

def load_summary() -> dict:
    if not SUMMARY_PATH.exists():
        print(f"[ERROR] Summary not found at {SUMMARY_PATH} — run generate_configs.py first")
        sys.exit(1)
    with SUMMARY_PATH.open() as f:
        return json.load(f)


def route_id(device_id: str, summary: dict) -> int:
    """Deterministic route ID based on device order in summary."""
    ids = sorted(summary["http_device_ports"].keys())
    return ROUTE_ID_BASE + ids.index(device_id)


def build_route_payload(device_id: str, port: int) -> dict:
    """
    Builds the APISIX route object for one HTTP device.

    The upstream uses HTTPS (scheme=https) since device containers serve TLS.
    pass_host=pass keeps the original Host header so the device can log it.
    """
    return {
        "name":     f"direct_{device_id}",
        "uri":      f"/device/{device_id}",
        "methods":  ["POST"],
        "upstream": {
            "type":      "roundrobin",
            "scheme":    "https",
            "pass_host": "pass",
            "nodes": {
                f"192.168.50.8:{port}": 1
            },
            # Skip TLS verification for the self-signed device cert
            "tls": {
                "verify": False
            }
        },
        "plugins": {
            "ext-plugin-post-req": {
                "conf" : [
                    {"name": "SimpleFilter", "value": "{\"enable\":\"feature\"}"},
                ]
            }   
        }
    }


def curl_upsert(route_id: int, payload: dict) -> str:
    """Returns the curl command to create/update a route."""
    body = json.dumps(payload)
    return (
        f'curl -s -o /dev/null -w "%{{http_code}}" -X PUT '
        f'"{APISIX_ADMIN}/apisix/admin/routes/{route_id}" '
        f'-H "X-API-KEY: {ADMIN_API_KEY}" '
        f'-H "Content-Type: application/json" '
        f"-d '{body}'"
    )


def curl_delete(route_id: int) -> str:
    """Returns the curl command to delete a route."""
    return (
        f'curl -s -o /dev/null -w "%{{http_code}}" -X DELETE '
        f'"{APISIX_ADMIN}/apisix/admin/routes/{route_id}" '
        f'-H "X-API-KEY: {ADMIN_API_KEY}"'
    )


def run_curl(cmd: str, description: str) -> None:
    """Runs a curl command and prints the result."""
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    status = result.stdout.strip()
    if status in ("200", "201"):
        print(f"  ✓ {description} (HTTP {status})")
    else:
        print(f"  ✗ {description} (HTTP {status}) — stderr: {result.stderr.strip()}")
        sys.exit(1)

# | ================= Commands ================= |

def cmd_print(summary: dict) -> None:
    """Prints curl commands without executing them."""
    ports = summary.get("http_device_ports", {})
    if not ports:
        print("[INFO] No HTTP devices found in summary.")
        return

    print(f"# APISIX routes for {len(ports)} HTTP device(s)")
    print(f"# Admin API: {APISIX_ADMIN}")
    print()
    for device_id, port in sorted(ports.items()):
        rid     = route_id(device_id, summary)
        payload = build_route_payload(device_id, port)
        print(f"# Route {rid}: {device_id} → 192.168.50.8:{port}")
        print(curl_upsert(rid, payload))
        print()

    print("# Verify:")
    print(f'curl -s "{APISIX_ADMIN}/apisix/admin/routes" -H "X-API-KEY: {ADMIN_API_KEY}" | python3 -m json.tool')


def cmd_apply(summary: dict) -> None:
    """Creates or updates all routes via the Admin API."""
    ports = summary.get("http_device_ports", {})
    if not ports:
        print("[INFO] No HTTP devices found in summary.")
        return

    print(f"[apply] Creating {len(ports)} route(s) on {APISIX_ADMIN}...")
    for device_id, port in sorted(ports.items()):
        rid     = route_id(device_id, summary)
        payload = build_route_payload(device_id, port)
        run_curl(
            curl_upsert(rid, payload),
            f"{device_id}  /device/{device_id} → 192.168.50.8:{port}"
        )
    print("[apply] Done.")


def cmd_delete(summary: dict) -> None:
    """Deletes all generated routes via the Admin API."""
    ports = summary.get("http_device_ports", {})
    if not ports:
        print("[INFO] No HTTP devices found in summary.")
        return

    print(f"[delete] Removing {len(ports)} route(s) from {APISIX_ADMIN}...")
    for device_id in sorted(ports.keys()):
        rid = route_id(device_id, summary)
        run_curl(curl_delete(rid), f"route {rid} ({device_id})")
    print("[delete] Done.")

# | ================= Main ================= |

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--apply",  action="store_true", help="Execute curl commands immediately")
    group.add_argument("--delete", action="store_true", help="Delete all generated routes")
    args = parser.parse_args()

    summary = load_summary()

    if args.apply:
        cmd_apply(summary)
    elif args.delete:
        cmd_delete(summary)
    else:
        cmd_print(summary)


if __name__ == "__main__":
    main()