# API Routes Reference

All routes are registered via `./configure.sh` using the APISIX Admin API.  

---

## Global Rate Limit

A global rate limit is enforced **before all route-level plugins**, across every endpoint:

| Parameter | Value |
|---|---|
| Max requests | 100 per second |
| Scope | All routes |
| Rejection code | `429 Too Many Requests` |
| Rejection message | `Too many requests — quota exceeded` |

---

## Plugin Execution Order

Plugins run in descending priority order:

```
client-control       (14000)  — body size guard
request-validation   (13500)  — schema validation
jwt-auth             (13000)  — JWT verification
serverless-pre-fn    (12500)  — role allowlist check
ext-plugin-post-req  (12000)  — Java plugin logic
```

---

## Authentication

Two authentication mechanisms are used depending on the route:

| Mechanism | Header | Used by |
|---|---|---|
| JWT Bearer token | `Authorization: Bearer <token>` | All `/onboarding/*` routes |
| Plain-text API key | `apikey: <key>` | `/command`, `/backendForward`, `/commands` |

JWT tokens are issued per consumer role. API keys are returned at registration time by `POST /onboarding/device` or `POST /onboarding/backend`.

### JWT Consumer Roles

| Consumer | Allowed routes |
|---|---|
| `gateway-admin` | All onboarding routes |
| `device-admin` | `/onboarding/translation`, `/onboarding/device`, `/onboarding/backendAuthZ`, `/onboarding/deviceAuthZ` |
| `backend-admin` | `/onboarding/backend`, `/onboarding/backendAuthZ`, `/onboarding/deviceAuthZ` |

---

## Routes

---

### `/onboarding/backend` and `/onboarding/device`

Communication configuration — register, update, and delete devices and backends.

---

#### `POST` — Register a new entity

- Registers a device or backend and returns a unique gateway ID and a plain-text API key.  
- For devices, a `listOfDevices` array allows batch registration in a single call.  
- For backends, an optional `security` field registers an outbound access token used by the gateway when calling that backend.

**Auth:** `Authorization: Bearer <token>` · **Roles:** `gateway-admin`, `device-admin` (devices) ,`backend-admin` (backends)  

**Register a backend:**

```bash
curl -X POST http://<api-gateway-ip>:9080/onboarding/backend \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "backendName": "solar-dashboard",
    "type": "solar",
    "infoEndpoint": "http://backend-IP:8000/info",
    "callbackEndpoint": "http://backend-IP:8000/callback",
    "security": {
      "type": "accessToken",
      "token": "my-backend-secret-token",
      "expiracyDate": "2026-12-31"
    }
  }'
```

**Register devices (batch):**

```bash
curl -X POST http://<api-gateway-ip>:9080/onboarding/device \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "listOfDevices": [
      {
        "deviceName": "inverter-01",
        "type": "inverter",
        "commands": {
          "setPower": {
            "name": "setPower",
            "params": {
              "level": { "type": "number", "description": "Power level in watts", "mandatory": true },
              "unit":  { "type": "string", "description": "Unit of measure",      "mandatory": false }
            }
          }
        }
      }
    ]
  }'
```

**Response `200`:**
```json
{
  "gatewayDeviceId": "device_<UUID>",
  "apiKey": "<plain-text-api-key>"
}
```

**Response `400`:** `Failed to create <Device|Backend>`

---

#### `PATCH` — Update communication configuration

Updates specific fields of an existing entity. Use top-level fields to modify values, and `fieldsToRemove` to delete specific fields — no full document replacement.

**Auth:** `Authorization: Bearer <token>`

**Update a backend:**

```bash
curl -X PATCH http://<api-gateway-ip>:9080/onboarding/backend \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "gatewayBackendId": "backend_<UUID>",
    "infoEndpoint": "http://backend-IP:9000/info",
    "fieldsToRemove": ["callbackEndpoint"]
  }'
```

**Update a device:**

```bash
curl -X PATCH http://<api-gateway-ip>:9080/onboarding/device \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "gatewayDeviceId": "device_<UUID>",
    "commands": {
      "setPower": {
        "params": {
          "level": { "type": "number", "description": "Updated description", "mandatory": true }
        }
      }
    },
    "fieldsToRemove": ["commands.setPower.params.unit"]
  }'
```

**Response `200`:**
```json
{ "status": "success", "message": "<Device|Backend> configuration updated successfully." }
```

**Response `400`:** `Failed to update <Device|Backend> configuration. <Device|Backend> may not exist or invalid request format.`

---

#### `DELETE` — Remove entity

Deletes the entity's configuration and cascades deletion to the authorization collection of the other entity type.

**Auth:** `Authorization: Bearer <token>`

**Delete a backend:**

```bash
curl -X DELETE http://<api-gateway-ip>:9080/onboarding/backend \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{ "gatewayBackendId": "backend_<UUID>" }'
```

**Delete a device:**

```bash
curl -X DELETE http://<api-gateway-ip>:9080/onboarding/device \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{ "gatewayDeviceId": "device_<UUID>" }'
```

**Response `200`:**
```json
{ "status": "success", "message": "Deleted the Device configuration and all related authorizations." }
```

**Response `400` (partial failures):**
```json
{ "status": "partial_failure", "message": "Failed to delete related authorizations. Device configuration deleted successfully." }
{ "status": "partial_failure", "message": "Failed to delete Device configuration. Related authorizations deleted successfully." }
{ "status": "failure",         "message": "Failed to delete Device configuration and related authorizations. Device may not exist or invalid request format." }
```

---

### `/onboarding/backendAuthZ` and `/onboarding/deviceAuthZ`

Authorization configuration — control which backends can send a command which devices, and which commands are permitted, but also which device can send information to which backend

---

#### `POST` — Create authorization entry

- **Backend entry:** maps each `gatewayDeviceId` to a list of permitted command names
- **Device entry:** holds a flat list of authorized `gatewayBackendId` values

**Auth:** `Authorization: Bearer <token>` · **Roles:** all three consumer roles  

**Backend authorization (which devices & commands a backend can use):**

```bash
curl -X POST http://<api-gateway-ip>:9080/onboarding/backendAuthZ \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "gatewayBackendId": "backend_<UUID>",
    "listOfAuthorizations": {
      "device_<UUID>": ["setPower", "getStatus"]
    }
  }'
```

**Device authorization (which backends a device accepts messages from):**

```bash
curl -X POST http://<api-gateway-ip>:9080/onboarding/deviceAuthZ \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "gatewayDeviceId": "device_<UUID>",
    "listOfAuthorizations": ["backend_<UUID1>", "backend_<UUID2>"]
  }'
```

**Response `200`:**
```json
{ "status": "success", "message": "Device entry added successfully." }
```

**Response `400`:**
```
Failed to add Device authorization entry. Device does not exist in configuration collection.
Failed to add Device authorization. Authorization entry may already exist or invalid request format.
```

---

#### `PATCH` — Update authorization entry

Incrementally adds or removes entries using `listOfAuthorizationsToAdd` and `listOfAuthorizationsToRemove`. Both can be used in the same request.

**Auth:** `Authorization: Bearer <token>`

**Update backend authorization:**

```bash
curl -X PATCH http://<api-gateway-ip>:9080/onboarding/backendAuthZ \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "gatewayBackendId": "backend_<UUID>",
    "listOfAuthorizationsToAdd":    { "device_<UUID>": ["reboot"] },
    "listOfAuthorizationsToRemove": { "device_<UUID>": ["getStatus"] }
  }'
```

**Update device authorization:**

```bash
curl -X PATCH http://<api-gateway-ip>:9080/onboarding/deviceAuthZ \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "gatewayDeviceId": "device_<UUID>",
    "listOfAuthorizationsToAdd":    ["backend_<UUID3>"],
    "listOfAuthorizationsToRemove": ["backend_<UUID2>"]
  }'
```

**Response `200`:**
```json
{ "status": "success", "message": "Device updated successfully." }
```

**Response `400`:** `Failed to update Device authorization entry. Device does not exist in configuration collection.`

---

#### `DELETE` — Remove authorization entry

Removes the entire authorization document for the entity. Does **not** cascade — cross-entity cleanup is handled by `DELETE /onboarding/device` or `DELETE /onboarding/backend`.

**Auth:** `Authorization: Bearer <token>`

**Delete backend authorization:**

```bash
curl -X DELETE http://<api-gateway-ip>:9080/onboarding/backendAuthZ \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{ "gatewayBackendId": "backend_<UUID>" }'
```

**Delete device authorization:**

```bash
curl -X DELETE http://<api-gateway-ip>:9080/onboarding/deviceAuthZ \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{ "gatewayDeviceId": "device_<UUID>" }'
```

**Response `200`:**
```json
{ "status": "success", "message": "Device and related authorizations deleted successfully." }
```

**Response `400`:** `Failed to delete Device. Device may not exist or invalid request format.`

---

### `GET /commands`

Returns the full list of devices a backend is authorized to command, with each device's name and command parameter schemas. The caller is identified by their `apikey` alone — no request body needed. Rejected if the key is not valid.

**Auth:** `apikey: <backend-api-key>` (returned at backend registration)  

```bash
curl -X GET http://<api-gateway-ip>:9080/commands \
  -H "apikey: <your-backend-api-key>"
```

**Response `200`:**
```json
{
  "status": "success",
  "authorizedDevices": {
    "device_<UUID>": {
      "deviceName": "inverter-01",
      "commands": {
        "setPower": {
          "params": {
            "level": { "type": "number", "description": "Power level in watts", "mandatory": true },
            "unit":  { "type": "string", "description": "Unit of measure",      "mandatory": false }
          }
        }
      }
    }
  }
}
```

**Response `403`:**
```json
{ "message": "Unauthorized access: API key belongs to a device" }
{ "message": "Invalid API key" }
```

**Response `400`:** `Failed to retrieve backend authorizations. Backend may not exist or invalid request format.`

---

### `POST/GET/DELETE /onboarding/translation`

Manages the protocol translation configuration for a device — tells the gateway how to map incoming commands to device-native calls (HTTP or MQTT).

**Auth:** `Authorization: Bearer <token>` · **Roles:** `gateway-admin`, `device-admin`  

---

#### `POST` — Create translation configuration

**HTTP device:**

```bash
curl -X POST http://<api-gateway-ip>:9080/onboarding/translation \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    {
    "gatewayDeviceId": "device_<UUID>",
    "adapter": "http",
    "security": "api key",
    "queuing": {
        "retryIntervalSeconds": 5,
        "maxTimeToLiveSeconds": 40
    },
    "commands": {
        "setBatteryOperation": {
            "name": "setBatteryOperation",
            "endpoint": "https://<device-IP>:8443/v2/schedule/",
            "method": "POST",
            "timeouts": {
                "connect": 2,
                "request": 10
            },
            "payloadTemplate": 
                {
                    "schedule": [
                        {
                            "type": "setBatteryOperation",
                            "operation": {
                                "dispatchPower": {
                                    "activePower": null
                                }
                            },
                            "startAt": null,
                            "endAt": null
                        }
                    ],
                    "assetIdentifiers": null
                },
            "mappings": {
                "activePower": "schedule[0].operation.dispatchPower.activePower",
                "reactivePower": "schedule[0].operation.dispatchPower.reactivePower",
                "maxRate": "schedule[0].operation.deliverFCR.maxRate",
                "Percentage": "schedule[0].operation.chargeToState.percentage",
                "startAt": "schedule[0].startAt",
                "endAt": "schedule[0].endAt",
                "assetIdentifiers": "assetIdentifiers"
            },
            "cleanup": {
                "emptyObjectToNull": true,
                "removeNulls": true,
                "removeEmpty": true
            }
        }
    }
}
  }'
```

**MQTT device:**

```bash
curl -X POST http://<api-gateway-ip>:9080/onboarding/translation \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
        "gatewayDeviceId": "device_<UUID>",
        "adapter": "mqtt",
        "queuing": {
            "retryIntervalSeconds": 5,
            "maxTimeToLiveSeconds": 40
        },
        "connection": {
            "qos": 1,
            "keepAliveInterval": 30,
            "connectionTimeout": 10,
            "cleanSession": true,
            "reconnectDelay": 5
        },

        "subscriptions": [
            {
                "topic": "devices/<MQTT_DEVICE_ID>/telemetry",
                "forwardToBackend": true
            },
            {
                "topic": "devices/<MQTT_DEVICE_ID>/status",
                "forwardToBackend": true
            }
        ],

      "commands": {
        "setPower": {
          "name": "setPower",
          "topic": "devices/<MQTT_DEVICE_ID>/commands",
          "responseTopic": "devices/<MQTT_DEVICE_ID>/telemetry",
          "timeouts": {
            "response": 10
          },
          "payloadTemplate": {
            "type": "setPower",
            "operation": {
              "dispatchPower": {}
            },
            "startAt": null,
            "endAt": null
          },
          "mappings": {
            "activePower":     "operation.dispatchPower.activePower",
            "reactivePower":   "operation.dispatchPower.reactivePower",
            "startAt":         "startAt",
            "endAt":           "endAt"
          },
          "cleanup": {
            "removeNulls":       true,
            "removeEmpty":       false,
            "emptyObjectToNull": false
          }
        }
      }
    }
  }'
```

**Response `200`:** `Device Translation Created`  
**Response `400`:** `CorruptedConfiguration: <message>`  
**Response `401`:** `{"message":"failed to verify jwt"}`

---

#### `GET` — Retrieve translation configuration

```bash
curl -X GET http://<api-gateway-ip>:9080/onboarding/translation \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{ "gatewayDeviceId": "device_<UUID>" }'
```

**Response `200`:** Full translation configuration object  
**Response `404`:** `X-Error: Unknown device - no config to retrieve`

---

#### `DELETE` — Delete translation configuration

Deletes the config and shuts down the device's adapter.

```bash
curl -X DELETE http://<api-gateway-ip>:9080/onboarding/translation \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{ "gatewayDeviceId": "device_<UUID>" }'
```

**Response `200`:** `Device Translation Config Deleted - success`  
**Response `404`:** `X-Error: Unknown device - no config to delete`

---

### `POST /command`

Dispatches a command to a device. The gateway authenticates the backend via API key, checks authorization, translates the command into the device's native protocol (HTTP or MQTT), and forwards it. If the device is unreachable and queuing is configured, the request is stored for retry.

**Auth:** `apikey: <backend-api-key>`

```bash
curl -X POST http://<api-gateway-ip>:9080/command \
  -H "apikey: <your-backend-api-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "gatewayDeviceId": "device_<UUID>",
    "command": "setPower",
    "params": {
      "level": 750,
      "unit": "W"
    }
  }'
```

**Response `200`:** Device's native response (forwarded as-is)

**Response `202` (queued — device temporarily unreachable):**
```json
{
  "status": "queued",
  "message": "Device unreachable - request queued for retry",
  "deviceId": "device_<UUID>",
  "queuedRequestId": "<request-UUID>"
}
```

**Response `400`:** `Bad Request: property "command" required`  
**Response `403`:** `Forbidden - IllegalOperation: Unauthorized access`  
**Response `502` (unreachable, no queuing configured):**
```json
{ "error": "Device unreachable: <reason>, device has no queuing mechanism setup" }
```

---

### `POST /backendForward`

Forwards a device message to all backends the device is authorized to reach. The device is identified by its API key — no JWT required.

**Auth:** `apikey: <device-api-key>`

```bash
curl -X POST http://<api-gateway-ip>:9080/backendForward \
  -H "apikey: <your-device-api-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "temperature": 42.5,
    "voltage": 230,
    "timestamp": "2025-05-16T10:00:00Z"
  }'
```

**Response `202`:**
```json
{ "status": "Forwarded" }
```

---

## Route Summary

| Route | Methods | Auth | Java plugin(s) |
|---|---|---|---|
| `/onboarding/device` | POST, PATCH, DELETE | JWT | Onboarding |
| `/onboarding/backend` | POST, PATCH, DELETE | JWT | Onboarding |
| `/onboarding/deviceAuthZ` | POST, PATCH, DELETE | JWT | Onboarding |
| `/onboarding/backendAuthZ` | POST, PATCH, DELETE | JWT | Onboarding |
| `/onboarding/translation` | POST, GET, DELETE | JWT | TranslationOnboarding |
| `/commands` | GET | apikey (backend) | Onboarding |
| `/command` | POST | apikey (backend) | AuthFilter, DeviceTranslation |
| `/backendForward` | POST | apikey (device) | AuthFilter, BackendForwarder |
