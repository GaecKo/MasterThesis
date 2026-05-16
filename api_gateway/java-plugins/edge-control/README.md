# Edge Control — Java Plugin

`edge-control` is a custom Apache APISIX external plugin written in Java (Spring Boot). It runs as a sidecar process inside the APISIX container and handles the core business logic of the gateway: device authentication, protocol translation, backend forwarding, and onboarding.

---

## Architecture

APISIX communicates with this plugin via a Unix socket using the [APISIX Java Plugin Runner](https://github.com/apache/apisix-java-plugin-runner) protocol. The plugin is launched automatically by APISIX on startup using the command defined in `config.yaml`:

```yaml
ext-plugin:
  cmd: ['java', '-jar', '-Xmx1g', '/usr/local/apisix/java-plugins/edge-control/target/edge-control-0.0.1-SNAPSHOT.jar']
```

---

## Filters (Plugin Entry Points)

Each filter maps to a named plugin invoked via `ext-plugin-post-req` in APISIX route configs:

| Filter name (APISIX) | Java class | Description |
|---|---|---|
| `AuthFilter` | `AuthFilter.java` | Authenticates and Authorizes devices and backends using gateway tokens |
| `DeviceTranslation` | `DeviceTranslationFilter.java` | Translates incoming commands to device-native protocol (HTTP or MQTT) |
| `TranslationOnboarding` | `TranslationOnboardingFilter.java` | Manages device translation configuration |
| `Onboarding` | `OnboardingFilter.java` | Handles device, backend, and authorization onboarding |
| `BackendForwarder` | `BackendForwarderFilter.java` | Forwards authenticated requests to the correct backend |

---

## Internal Modules

### `auth/`
Manages authentication and authorization:
- `AuthenticationManager` — validates gateway tokens
- `AuthorizationManager` — checks if a device/backend is authorized for an operation
- `AuthRegistry` — in-memory registry of authenticated sessions
- `backend/BackendManager` — manages backend identities
- `device/DeviceManager` — manages device identities
- `tokens/` — token creation, encryption (`GatewayTokensCrypto`), and registry

### `database/`
MongoDB repositories (via the MongoDB Java driver):
- `DeviceConfigRepository` — device registration records
- `BackendConfigRepository` — backend registration records
- `DeviceAuthorizationsRepository` — per-device authorization rules
- `BackendAuthorizationsRepository` — per-backend authorization rules
- `DevicesTranslationConfigRepository` — translation configs per device
- `GatewayTokensRepository` — persisted gateway tokens
- `QueueConfigRepository` / `QueuedRequestRepository` — queued request persistence

### `translation/`
Protocol translation engine:
- `DeviceTranslationManager` — orchestrates translation per device
- `adapter/` — device-specific adapters:
  - `HttpDeviceAdapter` — sends translated commands over HTTP
  - `MqttDeviceAdapter` — sends translated commands via MQTT
- `adapter/command/` — command definition and translation engine (path compilation, cleanup)
- `queuing/` — async request queuing for offline devices

---

## Building the Plugin

```bash
cd api_gateway/java-plugins/edge-control

# Build the JAR
./mvnw clean package -DskipTests

# Output:
# target/edge-control-0.0.1-SNAPSHOT.jar
```

The resulting JAR is automatically picked up by the Docker volume mount defined in `docker-compose.yml`:

```yaml
- ./java-plugins/edge-control/target/edge-control-0.0.1-SNAPSHOT.jar:
    /usr/local/apisix/java-plugins/edge-control/target/edge-control-0.0.1-SNAPSHOT.jar
```

> ⚠️ You must build the JAR before starting the Docker stack, otherwise the container will fail to launch the plugin.

---

## Logs


Plugin logs are written to the `logs/` directory, which is mounted into the container:

```yaml
- ./java-plugins/edge-control/logs:/usr/local/apisix/java-plugins/edge-control/
```

You can tail them directly:

```bash
tail -f api_gateway/java-plugins/edge-control/*.log
```
