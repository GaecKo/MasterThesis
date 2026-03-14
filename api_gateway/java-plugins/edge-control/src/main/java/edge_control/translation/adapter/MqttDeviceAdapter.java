package edge_control.translation.adapter;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.exceptions.IllegalOperation;
import edge_control.translation.adapter.command.definition.MqttCommandDefinition;
import edge_control.translation.adapter.command.engine.CommandTranslationEngine;
import edge_control.translation.config.DeviceConfig;
import edge_control.logger.EdgeControlLogger;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

public class MqttDeviceAdapter implements DeviceAdapter {

    private static final EdgeControlLogger logger   = EdgeControlLogger.getInstance();
    private static final ObjectMapper MAPPER        = new ObjectMapper();
    private static final String BACKEND_FORWARD_URL = "http://localhost:9080/backendForward";

    // ── Parsed config ─────────────────────────────────────────────────────────

    private String gatewayDeviceId;

    // Connection
    private String  brokerUrl;
    private int     qos;
    private int     keepAliveInterval;
    private int     connectionTimeout;
    private boolean cleanSession;
    private int     reconnectDelay;

    // Commands and subscriptions
    private final Map<String, MqttCommandDefinition> commandDefinitions = new HashMap<>();
    private final List<MqttSubscriptionConfig>       subscriptions      = new ArrayList<>();

    // ── Runtime state ─────────────────────────────────────────────────────────

    private MqttClient mqttClient;

    // Pending response futures keyed by correlationId
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingResponses
            = new ConcurrentHashMap<>();

    private final CommandTranslationEngine translationEngine = new CommandTranslationEngine();

    // ── Subscription config record ────────────────────────────────────────────

    private record MqttSubscriptionConfig(String topic, boolean forwardToBackend) {
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    public void init(DeviceConfig config) throws EdgeControlException {
        this.gatewayDeviceId = config.getDeviceId();
        JSONObject root = config.getConfig();

        logger.info("Initialising MQTT adapter for device: " + gatewayDeviceId);

        loadConnection(root);
        loadSubscriptions(root);
        loadCommands(root);
        connectToBroker();
    }

    // ── Config loaders ────────────────────────────────────────────────────────

    private void loadConnection(JSONObject root) throws CorruptedConfiguration {
        if (!root.has("connection")) {
            throw new CorruptedConfiguration(
                    "MQTT adapter for device " + gatewayDeviceId
                            + " is missing required 'connection' section");
        }
        JSONObject conn = root.getJSONObject("connection");

        this.brokerUrl          = "tcp://mosquitto:1883";
        this.qos                = conn.optInt("qos",               1);
        this.keepAliveInterval  = conn.optInt("keepAliveInterval", 30);
        this.connectionTimeout  = conn.optInt("connectionTimeout", 10);
        this.cleanSession       = conn.optBoolean("cleanSession",  true);
        this.reconnectDelay     = conn.optInt("reconnectDelay",    5);

        logger.debug("Connection config loaded — broker=" + brokerUrl
                + " qos=" + qos + " keepAlive=" + keepAliveInterval + "s");
    }

    private void loadSubscriptions(JSONObject root) throws CorruptedConfiguration {
        if (!root.has("subscriptions")) {
            logger.debug("No 'subscriptions' section found for device " + gatewayDeviceId);
            return;
        }

        JSONArray subsJson = root.getJSONArray("subscriptions");

        for (int i = 0; i < subsJson.length(); i++) {
            JSONObject sub = subsJson.getJSONObject(i);

            String topic = sub.optString("topic", null);
            if (topic == null || topic.isBlank()) {
                throw new CorruptedConfiguration(
                        "MQTT adapter for device " + gatewayDeviceId
                                + ": subscription[" + i + "] is missing 'topic'");
            }

            boolean forwardToBackend = sub.optBoolean("forwardToBackend", false);
            subscriptions.add(new MqttSubscriptionConfig(topic, forwardToBackend));
        }

        logger.info("Loaded " + subscriptions.size()
                + " subscriptions for device " + gatewayDeviceId);
    }

    private void loadCommands(JSONObject root) throws CorruptedConfiguration {
        if (!root.has("commands")) {
            throw new CorruptedConfiguration(
                    "MQTT adapter for device " + gatewayDeviceId
                            + " is missing required 'commands' section");
        }

        JSONObject commands = root.getJSONObject("commands");
        Iterator<String> commandNames = commands.keys();

        while (commandNames.hasNext()) {
            String commandName = commandNames.next();
            JSONObject commandJson = commands.getJSONObject(commandName);
            MqttCommandDefinition definition = new MqttCommandDefinition(commandName, commandJson);
            commandDefinitions.put(commandName, definition);
        }

        logger.info("Loaded " + commandDefinitions.size()
                + " command definitions for device " + gatewayDeviceId);
    }

    // ── Broker connection ─────────────────────────────────────────────────────

    private void connectToBroker() throws EdgeControlException {
        String clientId = "edge-control-" + gatewayDeviceId + "-" + UUID.randomUUID();

        try {
            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(cleanSession);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(connectionTimeout);
            options.setKeepAliveInterval(keepAliveInterval);
            options.setMaxReconnectDelay(reconnectDelay * 1000);

            // MqttCallbackExtended fires connectComplete() on every connect/reconnect,
            // which is essential to re-subscribe when cleanSession=true wipes them
            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    logger.info("MQTT " + (reconnect ? "re" : "") + "connected to "
                            + serverURI + " for device " + gatewayDeviceId);
                    subscribeToAllTopics();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    logger.error("MQTT connection lost for device " + gatewayDeviceId
                            + ": " + cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    onMessageArrived(topic,
                            new String(message.getPayload(), StandardCharsets.UTF_8));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // publish ACK — nothing to do
                }
            });

            mqttClient.connect(options);
            // connectComplete() fires immediately and calls subscribeToAllTopics()

        } catch (MqttException e) {
            throw new EdgeControlException(
                    "Failed to connect MQTT adapter for device " + gatewayDeviceId
                            + ": " + e.getMessage() + " (reason code: " + e.getReasonCode() + ")");
        }
    }

    /**
     * Subscribe to all declared subscription topics.
     * Called from connectComplete() so it runs on first connect AND every reconnect.
     */
    private void subscribeToAllTopics() {
        for (MqttSubscriptionConfig sub : subscriptions) {
            try {
                mqttClient.subscribe(sub.topic(), qos);
                logger.info("Subscribed to " + sub.topic());
            } catch (MqttException e) {
                logger.error("Failed to subscribe to " + sub.topic() + ": " + e.getMessage());
            }
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    @Override
    public void shutdown() {
        logger.info("Shutting down MQTT adapter for device: " + gatewayDeviceId);
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
            if (mqttClient != null) {
                mqttClient.close();
            }
        } catch (MqttException e) {
            logger.error("Error during MQTT shutdown: " + e.getMessage());
        }
        pendingResponses.forEach((id, future) ->
                future.completeExceptionally(new InterruptedException("Adapter shut down")));
        pendingResponses.clear();
    }

    // ── Request handling ──────────────────────────────────────────────────────

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response,
                              Runnable callback) throws Exception {

        if (!mqttClient.isConnected()) {
            response.setStatusCode(503);
            response.setBody("{\"error\":\"MQTT broker not connected\"}");
            callback.run();
            return;
        }

        if (request.getBody() == null || request.getBody().isEmpty()) {
            response.setStatusCode(400);
            response.setBody("{\"error\":\"Empty request body\"}");
            callback.run();
            return;
        }

        JsonNode backendRequest = MAPPER.readTree(request.getBody());

        if (backendRequest.get("command") == null || backendRequest.get("command").isNull()) {
            throw new CorruptedConfiguration("Missing 'command' field in request body");
        }

        String commandName = backendRequest.get("command").stringValue();
        MqttCommandDefinition commandDefinition = commandDefinitions.get(commandName);

        if (commandDefinition == null) {
            throw new IllegalOperation("Unknown command: " + commandName);
        }

        // Translate params into the device payload using the same engine as HTTP
        JsonNode finalPayload = translationEngine.translate(commandDefinition, backendRequest);

        // Build the MQTT envelope
        String correlationId = UUID.randomUUID().toString();
        JSONObject envelope = new JSONObject();
        envelope.put("deviceId",      gatewayDeviceId);
        envelope.put("timestamp",     Instant.now().toString());
        envelope.put("type",          "command");
        envelope.put("correlationId", correlationId);
        // responseTopic tells the device where to publish the ack — only set when a
        // response is expected, so the device knows whether to ack at all
        if (commandDefinition.hasResponseTopic()) {
            envelope.put("responseTopic", commandDefinition.getResponseTopic());
        }
        envelope.put("payload", new JSONObject(finalPayload.toString()));

        // Register pending future BEFORE publishing to avoid a response race
        CompletableFuture<String> responseFuture = null;
        if (commandDefinition.hasResponseTopic()) {
            responseFuture = new CompletableFuture<>();
            pendingResponses.put(correlationId, responseFuture);
        }

        String commandTopic = commandDefinition.getTopic();
        logger.debug("Publishing command='" + commandName + "' to topic='" + commandTopic
                + "' [correlationId=" + correlationId + "]");

        try {
            MqttMessage mqttMessage = new MqttMessage(
                    envelope.toString().getBytes(StandardCharsets.UTF_8));
            mqttMessage.setQos(qos);
            mqttClient.publish(commandTopic, mqttMessage);
        } catch (MqttException e) {
            if (responseFuture != null) pendingResponses.remove(correlationId);
            response.setStatusCode(502);
            response.setBody("{\"error\":\"Failed to publish MQTT command: " + e.getMessage() + "\"}");
            callback.run();
            return;
        }

        // Fire-and-forget: return 202 Accepted immediately
        if (!commandDefinition.hasResponseTopic()) {
            response.setStatusCode(202);
            response.setBody("{\"status\":\"sent\",\"correlationId\":\"" + correlationId + "\"}");
            response.setHeader("MODIFIED-BY", "EdgeControl/MQTT-Translation");
            callback.run();
            return;
        }

        // Wait for the device ack asynchronously
        Duration timeout = commandDefinition.getResponseTimeout();
        responseFuture
                .orTimeout(timeout.getSeconds(), TimeUnit.SECONDS)
                .thenAccept(responsePayload -> {
                    try {
                        response.setStatusCode(200);
                        response.setBody(responsePayload);
                        response.setHeader("MODIFIED-BY", "EdgeControl/MQTT-Translation");
                        logger.debug("Ack received for correlationId=" + correlationId);
                    } catch (Exception e) {
                        logger.error("Error setting MQTT response: " + e.getMessage());
                        response.setStatusCode(500);
                        response.setBody("{\"error\":\"Error processing MQTT response\"}");
                    } finally {
                        pendingResponses.remove(correlationId);
                        callback.run();
                    }
                })
                .exceptionally(throwable -> {
                    pendingResponses.remove(correlationId);
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    try {
                        if (cause instanceof TimeoutException) {
                            logger.error("MQTT response timeout [correlationId=" + correlationId
                                    + "] topic='" + commandTopic + "'");
                            response.setStatusCode(504);
                            response.setBody("{\"error\":\"Device did not respond within "
                                    + timeout.getSeconds() + "s\"}");
                        } else {
                            logger.error("MQTT async error: " + cause.getMessage());
                            response.setStatusCode(502);
                            response.setBody("{\"error\":\"" + cause.getMessage() + "\"}");
                        }
                        response.setHeader("MODIFIED-BY", "EdgeControl/MQTT-Translation");
                    } catch (Exception e) {
                        logger.error("Error setting error response: " + e.getMessage());
                    } finally {
                        callback.run();
                    }
                    return null;
                });
    }

    // ── Inbound message routing ───────────────────────────────────────────────

    private void onMessageArrived(String topic, String payload) {
        MqttSubscriptionConfig matched = subscriptions.stream()
                .filter(s -> s.topic().equals(topic))
                .findFirst()
                .orElse(null);

        if (matched == null) {
            logger.debug("Received message on undeclared topic: " + topic);
            return;
        }

        handleInboundMessage(payload, matched);
    }

    /**
     * Handles any inbound message regardless of topic semantics.
     * If the message carries a correlationId matching a pending request, that future
     * is resolved and the waiting handleRequest() unblocks.
     * Otherwise it is unsolicited — forwarded to backendForward if sub.forwardToBackend
     * is true, using the apikey embedded in the MQTT envelope for authorization.
     */
    private void handleInboundMessage(String payload, MqttSubscriptionConfig sub) {
        try {
            JSONObject json = new JSONObject(payload);

            if (json.has("correlationId")) {
                String correlationId = json.getString("correlationId");
                CompletableFuture<String> future = pendingResponses.get(correlationId);
                if (future != null) {
                    future.complete(payload);
                    return;
                }
            }

            // Unsolicited message
            logger.info("Inbound message on " + sub.topic() + " for device "
                    + gatewayDeviceId + ": " + payload);

            if (!sub.forwardToBackend()) return;

            String apiKey = json.optString("apikey", null);
            if (apiKey == null || apiKey.isBlank()) {
                logger.error("Cannot forward message from device " + gatewayDeviceId
                        + " — missing 'apikey' field in MQTT envelope");
                return;
            }

            // Strip apikey from the forwarded body — it belongs in the header only
            JSONObject forwardBody = new JSONObject(payload);
            forwardBody.remove("apikey");

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("apikey", apiKey);

            HttpForgery.doRequestAsync(
                            "POST",
                            BACKEND_FORWARD_URL,
                            forwardBody.toString(),
                            headers,
                            Duration.of(10, ChronoUnit.SECONDS),
                            Duration.of(30, ChronoUnit.SECONDS))
                    .thenAccept(result -> logger.debug(
                            "Forwarded to backendForward — status=" + result.statusCode()))
                    .exceptionally(throwable -> {
                        Throwable cause = throwable.getCause() != null
                                ? throwable.getCause() : throwable;
                        logger.error("Failed to forward to backendForward: " + cause.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            logger.error("Failed to handle inbound message on " + sub.topic()
                    + ": " + e.getMessage());
        }
    }
}