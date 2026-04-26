# Onboarding API — Route Reference

## Security Model

All onboarding routes are protected by two plugins running in the **rewrite phase**:

| Plugin | Priority | Role |
|---|---|---|
| `jwt-auth` | 13000 | Validates the Bearer JWT token |
| `serverless-pre-function` | 12500 | Checks consumer is authorized for this route |
| `ext-plugin-pre-req` (Onboarding) | 12000 | Handles the business logic |

Tokens must be passed as:
```
Authorization: <jwt-token>
```

### Consumer Permissions

| Consumer | `/onboarding/backend` | `/onboarding/backendAuthZ` | `/onboarding/device` | `/onboarding/deviceAuthZ` |
|---|---|---|---|---|
| `gateway-admin` | ✅ | ✅ | ✅ | ✅ |
| `backend-admin` | ✅ | ✅ | ❌ | ✅ |
| `device-admin` | ❌ | ✅ | ✅ | ✅ |

### Error Responses (Security Layer)

| Code | Cause |
|---|---|
| `401` | No token or invalid/expired JWT |
| `403` | Valid token but consumer not allowed on this route |

---

## Routes

---

### `POST /onboarding/backend`
Creates a new backend and returns its ID and API key.

**Allowed consumers:** `gateway-admin`, `backend-admin`

**Request body:**
```json
{
    "backenName": "backend1",
    "type": "Energy",
    "infoEndpoint": "https://xy/infoRetrieval"
}
```

**Response `200`:**
```json
{
    "message": "Backend created successfully.",
    "apiKey": "<generated-plaintext-api-key>",
    "gatewayBackendId": "backend_<uuid>"
}
```

**Response `400`** — invalid or missing configuration:
```json
{
    "status": "failure",
    "message": "..."
}
```

**Response `500`** — unexpected server error.

---

### `PATCH /onboarding/backend`
Updates an existing backend's configuration fields. Fields not included are left unchanged. Fields listed in `fieldsToRemove` are deleted.

**Allowed consumers:** `gateway-admin`, `backend-admin`

**Request body:**
```json
{
    "gatewayBackendId": "backend_<uuid>",
    "backenName": "backend6",
    "resource": "solar",
    "fieldsToRemove": ["type"]
}
```

| Field | Required | Description |
|---|---|---|
| `gatewayBackendId` | ✅ | ID of the backend to update |
| any other field | ❌ | Field to add or update |
| `fieldsToRemove` | ❌ | Array of field names to delete |

**Response `200`:**
```json
{
    "status": "success",
    "message": "Backend configuration updated successfully."
}
```

**Response `400`:**
```json
{
    "status": "failure",
    "message": "Failed to update backend configuration. Backend may not exist or invalid request format."
}
```

---

### `DELETE /onboarding/backend`
Deletes a backend and removes it from all device authorization entries.

**Allowed consumers:** `gateway-admin`, `backend-admin`

**Request body:**
```json
{
    "gatewayBackendId": "backend_<uuid>"
}
```

**Response `200` — full success:**
```json
{
    "status": "success",
    "message": "Deleted the backend configuration and all related authorizations."
}
```

**Response `200` — partial failure:**
```json
{
    "status": "partial_failure",
    "message": "Failed to delete related authorizations. Backend configuration deleted successfully."
}
```

**Response `400` — complete failure:**
```json
{
    "status": "failure",
    "message": "Failed to delete backend configuration and related authorizations. Backend may not exist or invalid request format."
}
```

---

### `POST /onboarding/backendAuthZ`
Creates a new authorization entry for a backend, specifying which devices it can communicate with and which commands are allowed per device.

> **Prerequisite:** The backend must already exist in the configuration collection (created via `POST /onboarding/backend`). If it does not, the request will be rejected with a `400`.

**Allowed consumers:** `gateway-admin`, `backend-admin`, `device-admin`

**Request body:**
```json
{
    "gatewayBackendId": "backend_<uuid>",
    "listOfAuthorizations": {
        "<gatewayDeviceId>": ["commandID1", "commandID2"],
        "<gatewayDeviceId2>": ["commandID3"]
    }
}
```

| Field | Required | Description |
|---|---|---|
| `gatewayBackendId` | ✅ | ID of the backend |
| `listOfAuthorizations` | ✅ | Map of device IDs to allowed command IDs |

**Response `200`:**
```json
{
    "status": "success",
    "message": "Backend authorization entry added successfully."
}
```

**Response `400`** — backend does not exist in config collection:
```json
{
    "status": "failure",
    "message": "Failed to add backend authorization entry. Backend does not exist in configuration collection."
}
```

**Response `400`** — authorization entry already exists or invalid format:
```json
{
    "status": "failure",
    "message": "Failed to add backend authorization entry. Backend may already exist or invalid request format."
}
```

---

### `PATCH /onboarding/backendAuthZ`
Updates an existing backend authorization entry by adding or removing device-command pairs. Can do both in a single request.

**Allowed consumers:** `gateway-admin`, `backend-admin`, `device-admin`

**Request body:**
```json
{
    "gatewayBackendId": "backend_<uuid>",
    "listOfAuthorizationsToAdd": {
        "<gatewayDeviceId>": ["commandID8"]
    },
    "listOfAuthorizationsToRemove": {
        "<gatewayDeviceId>": ["commandID1"],
        "<gatewayDeviceId2>": ["commandID3"]
    }
}
```

| Field | Required | Description |
|---|---|---|
| `gatewayBackendId` | ✅ | ID of the backend |
| `listOfAuthorizationsToAdd` | ❌ | Device→commands map to add |
| `listOfAuthorizationsToRemove` | ❌ | Device→commands map to remove |

At least one of `listOfAuthorizationsToAdd` or `listOfAuthorizationsToRemove` must be present.

**Response `200`:**
```json
{
    "status": "success",
    "message": "Backend authorization updated successfully."
}
```

**Response `400`:**
```json
{
    "status": "failure",
    "message": "Failed to update backend authorization. Backend may not exist or invalid request format."
}
```

---

### `DELETE /onboarding/backendAuthZ`
Deletes the entire authorization entry for a backend.

**Allowed consumers:** `gateway-admin`, `backend-admin`, `device-admin`

**Request body:**
```json
{
    "gatewayBackendId": "backend_<uuid>"
}
```

**Response `200`:**
```json
{
    "status": "success",
    "message": "Backend and related authorizations deleted successfully."
}
```

**Response `400`:**
```json
{
    "status": "failure",
    "message": "Failed to delete backend. Backend may not exist or invalid request format."
}
```

---

### `POST /onboarding/device`
Creates one or more devices and returns their IDs and API keys.

**Allowed consumers:** `gateway-admin`, `device-admin`

**Request body:**
```json
{
    "listOfDevices": [
        {
            "deviceName": "device3",
            "type": "solar",
            "commands": {
                "setBatteryOperation": {
                    "name": "setBatteryOperation",
                    "params": {
                        "assetIdentifier": { "type": "string[]", "description": "Asset IDs for this device", "mandatory": true },
                        "type": { "type": "string", "description": "The command", "mandatory": true },
                        "activePower": { "type": "number", "description": "Active power in kW", "mandatory": false },
                        "reactivePower": { "type": "number", "description": "Reactive power in kVar", "mandatory": false },
                        "maxRate": { "type": "number", "description": "Maximum deliverFCR", "mandatory": false },
                        "percentage": { "type": "number", "description": "chargeToState percentage", "mandatory": false },
                        "startAt": { "type": "string", "description": "Start datetime: year-month-dayT00:00:00Z", "mandatory": false },
                        "endAt": { "type": "string", "description": "End datetime: year-month-dayT00:00:00Z", "mandatory": false }
                    }
                }
            }
        }
    ]
}
```

Multiple devices can be created in a single request by adding more objects to `listOfDevices`.

**Response `200`:**
```json
{
    "device3": {
        "apiKey": "<generated-plaintext-api-key>",
        "gatewayDeviceId": "device_<uuid>"
    }
}
```

One entry per device name. The `apiKey` is returned in plaintext only at creation time — store it securely.

**Response `400`** — invalid or missing configuration.

---

### `PATCH /onboarding/device`
Updates an existing device configuration. Supports deep merge for nested fields using dot notation in `fieldsToRemove`.

**Allowed consumers:** `gateway-admin`, `device-admin`

**Request body:**
```json
{
    "gatewayDeviceId": "device_<uuid>",
    "deviceName": "device3",
    "commands": {
        "setBatteryOperation": {
            "params": {
                "assetIdentifier": { "mandatory": true }
            }
        }
    },
    "fieldsToRemove": ["commands.setEnergyOperation.params"]
}
```

| Field | Required | Description |
|---|---|---|
| `gatewayDeviceId` | ✅ | ID of the device to update |
| any other field | ❌ | Field to add or update (deep merge for nested objects) |
| `fieldsToRemove` | ❌ | Dot-notation paths of fields to remove |

**Response `200`:**
```json
{
    "status": "success"
}
```

**Response `400`:**
```json
{
    "status": "failure"
}
```

---

### `DELETE /onboarding/device`
Deletes a device and removes it from all backend authorization entries.

**Allowed consumers:** `gateway-admin`, `device-admin`

**Request body:**
```json
{
    "gatewayDeviceId": "device_<uuid>"
}
```

**Response `200` — full success:**
```json
{
    "status": "success",
    "message": "Deleted the backend configuration and all related authorizations."
}
```

**Response `200` — partial failure:**
```json
{
    "status": "partial_failure",
    "message": "Failed to delete related authorizations. Backend configuration deleted successfully."
}
```

**Response `400` — complete failure:**
```json
{
    "status": "failure",
    "message": "Failed to delete backend configuration and related authorizations. Backend may not exist or invalid request format."
}
```

---

### `POST /onboarding/deviceAuthZ`
Creates a new authorization entry for a device, specifying which backends it is allowed to communicate with.

> **Prerequisite:** The device must already exist in the configuration collection (created via `POST /onboarding/device`). If it does not, the request will be rejected with a `400`.

**Allowed consumers:** `gateway-admin`, `device-admin`, `backend-admin`

**Request body:**
```json
{
    "gatewayDeviceId": "device_<uuid>",
    "listOfAuthorizations": [
        "backend_<uuid1>",
        "backend_<uuid2>"
    ]
}
```

| Field | Required | Description |
|---|---|---|
| `gatewayDeviceId` | ✅ | ID of the device |
| `listOfAuthorizations` | ✅ | Array of authorized backend IDs (must be non-empty) |

**Response `200`:**
```json
{
    "status": "success",
    "message": "Device authorization added successfully."
}
```

**Response `400`** — device does not exist in config collection:
```json
{
    "status": "failure",
    "message": "Failed to add device authorization entry. Device does not exist in configuration collection."
}
```

**Response `400`** — authorization entry already exists or invalid format:
```json
{
    "status": "failure",
    "message": "Failed to add device authorization. Authorization entry may already exist or invalid request format."
}
```

---

### `PATCH /onboarding/deviceAuthZ`
Updates an existing device authorization entry by adding or removing backend IDs.

**Allowed consumers:** `gateway-admin`, `device-admin`, `backend-admin`

**Request body:**
```json
{
    "gatewayDeviceId": "device_<uuid>",
    "listOfAuthorizationsToAdd": ["backend_<uuid1>"],
    "listOfAuthorizationsToRemove": ["backend_<uuid2>"]
}
```

| Field | Required | Description |
|---|---|---|
| `gatewayDeviceId` | ✅ | ID of the device |
| `listOfAuthorizationsToAdd` | ❌ | Backend IDs to add |
| `listOfAuthorizationsToRemove` | ❌ | Backend IDs to remove |

At least one of `listOfAuthorizationsToAdd` or `listOfAuthorizationsToRemove` must be present.

**Response `200`:**
```json
{
    "status": "success"
}
```

**Response `400`:**
```json
{
    "status": "failure"
}
```

---

### `DELETE /onboarding/deviceAuthZ`
Deletes the entire authorization entry for a device.

**Allowed consumers:** `gateway-admin`, `device-admin`, `backend-admin`

**Request body:**
```json
{
    "gatewayDeviceId": "device_<uuid>"
}
```

**Response `200`:**
```json
{
    "status": "success",
    "message": "Device and related authorizations deleted successfully."
}
```

**Response `400`:**
```json
{
    "status": "failure",
    "message": "Failed to delete device. Device may not exist or invalid request format."
}
```

---