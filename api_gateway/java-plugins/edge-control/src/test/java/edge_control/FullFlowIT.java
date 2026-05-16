package edge_control;

import com.sun.net.httpserver.HttpServer;
import edge_control.auth.backend.BackendManager;
import edge_control.auth.device.DeviceManager;
import edge_control.exceptions.EdgeControlException;
import edge_control.translation.DeviceTranslationManager;
import edge_control.translation.adapter.AdapterCallback;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.translation.registry.DeviceRegistry;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.bson.Document;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Full-flow integration test exercising the complete gateway pipeline:
 *
 *   Step 1 — Create a backend via BackendManager
 *   Step 2 — Create an HTTP device and an MQTT device via DeviceManager
 *   Step 3 — Create translation configs (adapters) via DeviceTranslationManager
 *   Step 4 — Authorise the backend to command both devices
 *   Step 5 — Send a command through each adapter and verify the response
 *   Step 6 — Clean up all created entities via the managers
 *
 * Infrastructure: Testcontainers for MongoDB and Mosquitto; an embedded
 * HTTP server as the HTTP device stub. All data flows through actual
 * managers -> registries -> repositories — no direct database access.
 *
 * JSON bodies are stored as files under src/test/resources/
 * with {{PLACEHOLDER}} markers replaced at runtime.
 *
 * IMPORTANT: the system property MONGO_URI must be set BEFORE any manager
 * singleton is instantiated, so that MongoClientProvider picks up the test
 * container's connection string. This is handled by setting the property in
 * @BeforeAll before calling any getInstance().
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullFlowIT {

    // ==================== Containers ====================

    @Container
    static MongoDBContainer mongoContainer =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @Container
    static GenericContainer<?> mosquittoContainer =
            new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0"))
                    .withExposedPorts(1883)
                    .withCopyToContainer(
                            MountableFile.forClasspathResource("mosquitto-test.conf"),
                            "/mosquitto-no-auth.conf")
                    .withCommand("mosquitto -c /mosquitto-no-auth.conf");

    // ==================== HTTP device stub ====================

    private static HttpServer httpDeviceStub;
    private static int httpDevicePort;
    private static String lastReceivedHttpBody;

    // ==================== MQTT verification client ====================

    private static MqttClient mqttVerifier;
    private static String testBrokerUrl;

    // ==================== Created entity IDs (populated across ordered tests) ====================

    private String backendId;
    private String backendApiKey;
    private String httpDeviceId;
    private String httpDeviceApiKey;
    private String mqttDeviceId;
    private String mqttDeviceApiKey;

    // ==================== File helper ====================

    /**
     * Reads a JSON from src/test/resources/ and replaces
     * all {{KEY}} placeholders with the corresponding values from the map.
     */
    private static String loadResource(String filename, Map<String, String> replacements) {
        String path = filename;
        try (InputStream is = FullFlowIT.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("File not found: " + path);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                content = content.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return content;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file: " + path, e);
        }
    }

    private static String loadResource(String filename) {
        return loadResource(filename, Map.of());
    }

    // ==================== Setup / Teardown ====================

    @BeforeAll
    void setup() throws Exception {
        // --- CRITICAL: set MONGO_URI before any singleton loads MongoClientProvider ---
        System.setProperty("MONGO_URI", mongoContainer.getReplicaSetUrl());

        // --- Create a test encryption key file ---
        java.nio.file.Path keyFile = java.nio.file.Files.createTempFile("test-token-key-", ".key");
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        java.nio.file.Files.writeString(keyFile, java.util.Base64.getEncoder().encodeToString(keyBytes));
        keyFile.toFile().deleteOnExit();
        System.setProperty("TOKEN_KEY_PATH", keyFile.toString());

        // --- HTTP device stub ---
        httpDeviceStub = HttpServer.create(new InetSocketAddress(0), 0);
        httpDevicePort = httpDeviceStub.getAddress().getPort();
        httpDeviceStub.createContext("/v2/schedule/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastReceivedHttpBody = new String(body, StandardCharsets.UTF_8);
            String resp = "{\"status\":\"ok\"}";
            byte[] respBytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(respBytes); }
        });
        httpDeviceStub.start();

        // --- MQTT verification client ---
        testBrokerUrl = "tcp://" + mosquittoContainer.getHost()
                + ":" + mosquittoContainer.getMappedPort(1883);
        mqttVerifier = new MqttClient(testBrokerUrl,
                "test-verifier-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        mqttVerifier.connect(opts);
    }

    @AfterAll
    void teardown() throws Exception {
        if (httpDeviceStub != null) httpDeviceStub.stop(0);
        if (mqttVerifier != null && mqttVerifier.isConnected()) {
            mqttVerifier.disconnect();
            mqttVerifier.close();
        }
    }

    // ==================== Step 1: Create backend ====================

    @Test
    @Order(1)
    @DisplayName("Step 1 — Create backend")
    void createBackend() throws EdgeControlException {
        String body = loadResource("create_backend.json");

        Document result = BackendManager.getInstance().createBackend(body);
        assertThat(result).isNotNull();

        backendId     = result.getString("gatewayBackendId");
        backendApiKey = result.getString("apiKey");

        assertThat(backendId).as("Backend ID").startsWith("backend_");
        assertThat(backendApiKey).as("Backend API key").isNotBlank();

        System.out.println("[test] Backend created: id=" + backendId);
    }

    // ==================== Step 2: Create devices ====================

    @Test
    @Order(2)
    @DisplayName("Step 2a — Create HTTP device")
    void createHttpDevice() {
        String body = loadResource("create_device_http.json");

        Document result = DeviceManager.getInstance().createDevice(body);
        assertThat(result).isNotNull();

        // Response: {"device-http-test": {"apiKey": "...", "gatewayDeviceId": "device_..."}}
        Document inner = result.get("device-http-test", Document.class);
        assertThat(inner).as("Response should contain device-http-test key").isNotNull();

        httpDeviceId     = inner.getString("gatewayDeviceId");
        httpDeviceApiKey = inner.getString("apiKey");

        assertThat(httpDeviceId).startsWith("device_");
        assertThat(httpDeviceApiKey).isNotBlank();

        System.out.println("[test] HTTP device created: id=" + httpDeviceId);
    }

    @Test
    @Order(3)
    @DisplayName("Step 2b — Create MQTT device")
    void createMqttDevice() {
        String body = loadResource("create_device_mqtt.json");

        Document result = DeviceManager.getInstance().createDevice(body);
        assertThat(result).isNotNull();

        // Response: {"device-mqtt-test": {"apiKey": "...", "gatewayDeviceId": "device_..."}}
        Document inner = result.get("device-mqtt-test", Document.class);
        assertThat(inner).as("Response should contain device-mqtt-test key").isNotNull();

        mqttDeviceId     = inner.getString("gatewayDeviceId");
        mqttDeviceApiKey = inner.getString("apiKey");

        assertThat(mqttDeviceId).startsWith("device_");
        assertThat(mqttDeviceApiKey).isNotBlank();

        System.out.println("[test] MQTT device created: id=" + mqttDeviceId);
    }

    // ==================== Step 3: Create translation configs ====================

    @Test
    @Order(4)
    @DisplayName("Step 3a — Create HTTP adapter (translation config)")
    void createHttpAdapter() {
        String body = loadResource("create_adapter_http.json", Map.of(
                "HTTP_DEVICE_ID",       httpDeviceId,
                "HTTP_DEVICE_ENDPOINT", "http://localhost:" + httpDevicePort + "/v2/schedule/"
        ));

        assertThatCode(() -> DeviceTranslationManager.getInstance().createAdapter(body))
                .doesNotThrowAnyException();

        DeviceAdapter adapter = DeviceRegistry.getInstance().getAdapter(httpDeviceId);
        assertThat(adapter).as("HTTP adapter should be registered").isNotNull();

        System.out.println("[test] HTTP adapter created for " + httpDeviceId);
    }

    @Test
    @Order(5)
    @DisplayName("Step 3b — Create MQTT adapter (translation config)")
    void createMqttAdapter() {
        String body = loadResource("create_adapter_mqtt.json", Map.of(
                "MQTT_DEVICE_ID",  mqttDeviceId,
                "MQTT_BROKER_URL", testBrokerUrl
        ));

        assertThatCode(() -> DeviceTranslationManager.getInstance().createAdapter(body))
                .doesNotThrowAnyException();

        DeviceAdapter adapter = DeviceRegistry.getInstance().getAdapter(mqttDeviceId);
        assertThat(adapter).as("MQTT adapter should be registered").isNotNull();

        // Give the adapter time to connect to the broker and subscribe
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        System.out.println("[test] MQTT adapter created for " + mqttDeviceId);
    }

    // ==================== Step 4: Authorise ====================

    @Test
    @Order(6)
    @DisplayName("Step 4 — Set up authorisations")
    void setupAuthorisations() throws EdgeControlException {
        // Backend -> both devices (single call)
        String backendAuthBody = new JSONObject()
                .put("gatewayBackendId", backendId)
                .put("listOfAuthorizations", new JSONObject()
                        .put(httpDeviceId, new org.json.JSONArray().put("setBatteryOperation"))
                        .put(mqttDeviceId, new org.json.JSONArray().put("setPower"))
                )
                .toString();

        Document backendAuthResult = BackendManager.getInstance()
                .addBackendAuthorizationConfig(backendAuthBody);
        assertThat(backendAuthResult).isNotNull();

        // HTTP device -> backend
        String httpDevAuthBody = new JSONObject()
                .put("gatewayDeviceId", httpDeviceId)
                .put("listOfAuthorizations", new org.json.JSONArray().put(backendId))
                .toString();

        Document httpDevAuthResult = DeviceManager.getInstance()
                .addDeviceAuthorizationConfig(httpDevAuthBody);
        assertThat(httpDevAuthResult).isNotNull();

        // MQTT device -> backend
        String mqttDevAuthBody = new JSONObject()
                .put("gatewayDeviceId", mqttDeviceId)
                .put("listOfAuthorizations", new org.json.JSONArray().put(backendId))
                .toString();

        Document mqttDevAuthResult = DeviceManager.getInstance()
                .addDeviceAuthorizationConfig(mqttDevAuthBody);
        assertThat(mqttDevAuthResult).isNotNull();

        System.out.println("[test] Authorisations configured");
    }

    // ==================== Step 5: Send commands ====================

    @Test
    @Order(7)
    @DisplayName("Step 5a — Send HTTP command and verify device receives it")
    void sendHttpCommand() throws Exception {
        DeviceAdapter adapter = DeviceRegistry.getInstance().getAdapter(httpDeviceId);
        assertThat(adapter).isNotNull();

        JSONObject commandBody = new JSONObject()
                .put("command", "setBatteryOperation")
                .put("params", new JSONObject()
                        .put("activePower", 5000)
                        .put("reactivePower", 1000)
                        .put("startAt", "2025-01-01T00:00:00Z")
                        .put("endAt", "2025-01-02T00:00:00Z")
                        .put("assetIdentifiers", new org.json.JSONArray().put("asset_42"))
                );

        // Mock APISIX request/response
        HttpRequest request = mock(HttpRequest.class);
        when(request.getBody()).thenReturn(commandBody.toString());
        when(request.getHeaders()).thenReturn(new HashMap<>(Map.of(
                "Content-Type", "application/json",
                "apikey", backendApiKey
        )));

        HttpResponse response = mock(HttpResponse.class);
        CompletableFuture<Integer> capturedStatus = new CompletableFuture<>();
        CompletableFuture<String> capturedBody = new CompletableFuture<>();
        doAnswer(inv -> { capturedStatus.complete(inv.getArgument(0)); return null; })
                .when(response).setStatusCode(anyInt());
        doAnswer(inv -> { if (!capturedBody.isDone()) capturedBody.complete(inv.getArgument(0)); return null; })
                .when(response).setBody(anyString());

        CompletableFuture<Boolean> callbackResult = new CompletableFuture<>();
        AdapterCallback callback = new AdapterCallback() {
            @Override public void onSuccess() { callbackResult.complete(true); }
            @Override public void onDeviceUnreachable(String reason) { callbackResult.complete(false); }
        };

        // Fire
        adapter.handleRequest(request, response, callback);

        // Wait and verify
        assertThat(callbackResult.get(15, TimeUnit.SECONDS))
                .as("HTTP command should succeed").isTrue();
        assertThat(capturedStatus.get(1, TimeUnit.SECONDS))
                .as("Device stub should return 200").isEqualTo(200);
        assertThat(lastReceivedHttpBody)
                .as("Stub should have received the translated payload").isNotNull();

        // Verify translation happened (payload should contain schedule structure)
        JSONObject received = new JSONObject(lastReceivedHttpBody);
        assertThat(received.has("schedule"))
                .as("Translated payload should contain 'schedule'").isTrue();

        System.out.println("[test] HTTP command OK — stub received: "
                + lastReceivedHttpBody.substring(0, Math.min(120, lastReceivedHttpBody.length())) + "...");
    }

    @Test
    @Order(8)
    @DisplayName("Step 5b — Send MQTT command and verify round-trip with ack")
    void sendMqttCommand() throws Exception {
        DeviceAdapter adapter = DeviceRegistry.getInstance().getAdapter(mqttDeviceId);
        assertThat(adapter).isNotNull();

        String commandTopic  = "devices/" + mqttDeviceId + "/commands";
        String responseTopic = "devices/" + mqttDeviceId + "/telemetry";

        // Subscribe to command topic; simulate device ack when message arrives
        CompletableFuture<String> publishedMessage = new CompletableFuture<>();
        mqttVerifier.subscribe(commandTopic, 0, (topic, msg) -> {
            String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
            publishedMessage.complete(payload);

            // Echo back correlationId on the response topic
            JSONObject incoming = new JSONObject(payload);
            if (incoming.has("correlationId")) {
                JSONObject ack = new JSONObject()
                        .put("correlationId", incoming.getString("correlationId"))
                        .put("status", "ok");
                try {
                    mqttVerifier.publish(responseTopic,
                            new MqttMessage(ack.toString().getBytes(StandardCharsets.UTF_8)));
                } catch (MqttException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        Thread.sleep(500); // let subscription propagate

        JSONObject commandBody = new JSONObject()
                .put("command", "setPower")
                .put("params", new JSONObject()
                        .put("activePower", 3000)
                        .put("reactivePower", 500)
                );

        HttpRequest request = mock(HttpRequest.class);
        when(request.getBody()).thenReturn(commandBody.toString());
        when(request.getHeaders()).thenReturn(new HashMap<>(Map.of(
                "Content-Type", "application/json",
                "apikey", backendApiKey
        )));

        HttpResponse response = mock(HttpResponse.class);
        CompletableFuture<Integer> capturedStatus = new CompletableFuture<>();
        doAnswer(inv -> { capturedStatus.complete(inv.getArgument(0)); return null; })
                .when(response).setStatusCode(anyInt());
        doAnswer(inv -> null).when(response).setBody(anyString());

        CompletableFuture<Boolean> callbackResult = new CompletableFuture<>();
        AdapterCallback callback = new AdapterCallback() {
            @Override public void onSuccess() { callbackResult.complete(true); }
            @Override public void onDeviceUnreachable(String reason) { callbackResult.complete(false); }
        };

        // Fire
        adapter.handleRequest(request, response, callback);

        // Verify the adapter published to the command topic
        String published = publishedMessage.get(15, TimeUnit.SECONDS);
        assertThat(published).isNotNull();
        JSONObject publishedJson = new JSONObject(published);
        assertThat(publishedJson.has("correlationId")).isTrue();
        assertThat(publishedJson.getString("type")).isEqualTo("command");
        assertThat(publishedJson.has("payload")).isTrue();

        // Verify the adapter received the ack
        assertThat(callbackResult.get(15, TimeUnit.SECONDS))
                .as("MQTT command should succeed after device ack").isTrue();
        assertThat(capturedStatus.get(1, TimeUnit.SECONDS))
                .as("Should return 200 after ack").isEqualTo(200);

        System.out.println("[test] MQTT command OK — published: "
                + published.substring(0, Math.min(120, published.length())) + "...");
    }

    // ==================== Step 6: Cleanup ====================

    @Test
    @Order(9)
    @DisplayName("Step 6 — Delete all created entities")
    void cleanup() throws EdgeControlException {
        String httpDeviceBody = new JSONObject()
                .put("gatewayDeviceId", httpDeviceId).toString();
        String mqttDeviceBody = new JSONObject()
                .put("gatewayDeviceId", mqttDeviceId).toString();
        String backendBody = new JSONObject()
                .put("gatewayBackendId", backendId).toString();

        // Delete translation configs
        assertThat(DeviceTranslationManager.getInstance().deleteDeviceConfig(httpDeviceBody))
                .as("HTTP translation config deletion").isTrue();
        assertThat(DeviceTranslationManager.getInstance().deleteDeviceConfig(mqttDeviceBody))
                .as("MQTT translation config deletion").isTrue();

        // Delete device authorisations
        DeviceManager.getInstance().deleteDeviceAuthorizationConfig(httpDeviceBody);
        DeviceManager.getInstance().deleteDeviceAuthorizationConfig(mqttDeviceBody);

        // Delete backend authorisation
        BackendManager.getInstance().deleteBackendAuthorizationConfig(backendBody);

        // Delete devices
        Document httpDel = DeviceManager.getInstance().deleteDevice(httpDeviceBody);
        assertThat(httpDel).isNotNull();
        Document mqttDel = DeviceManager.getInstance().deleteDevice(mqttDeviceBody);
        assertThat(mqttDel).isNotNull();

        // Delete backend
        Document backendDel = BackendManager.getInstance().deleteBackend(backendBody);
        assertThat(backendDel).isNotNull();

        // Verify adapters are gone
        assertThat(DeviceRegistry.getInstance().getAdapter(httpDeviceId)).isNull();
        assertThat(DeviceRegistry.getInstance().getAdapter(mqttDeviceId)).isNull();

        System.out.println("[test] All entities deleted — cleanup complete");
    }
}