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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/**
 * DeviceAdapter implementation for MQTT-based devices.
 *
 * Manages a persistent connection to an MQTT broker, translates incoming HTTP commands
 * into MQTT messages using the configured payload templates and mappings, and subscribes
 * to device topics to receive telemetry and forward it to the backend.
 *
 * Supports both plain MQTT (mqtt:// or tcp://) and TLS-secured MQTTS (mqtts:// or ssl://).
 * The broker URL scheme determines whether TLS is applied.
 *
 * Subscriptions are re-established automatically after reconnection since cleanSession=true
 * wipes them on disconnect.
 */
public class MqttDeviceAdapter implements DeviceAdapter {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();
    private static final ObjectMapper MAPPER      = new ObjectMapper();

    // Internal APISIX backendForward route used to push unsolicited device messages upstream
    private static final String BACKEND_FORWARD_URL = "http://localhost:9080/backendForward";

    // Mosquitto uses the same certificate as APISIX since they share the same gateway VM
    private static final String APISIX_CERT_PATH = "/usr/local/apisix/conf/server.crt";

    // Shared formatter for timing logs, static since it has no instance-specific state
    private static final DateTimeFormatter TIMING_FORMATTER =
            DateTimeFormatter.ofPattern("hh:mm:ss.SSSS").withZone(ZoneId.systemDefault());

    // | ================= Parsed config ================= |

    private String  gatewayDeviceId;
    private String  brokerUrl;
    private boolean useTls;
    private int     qos;
    private int     keepAliveInterval;
    private int     connectionTimeout;
    private boolean cleanSession;
    private int     reconnectDelay;

    private final Map<String, MqttCommandDefinition> commandDefinitions = new HashMap<>();
    private final List<MqttSubscriptionConfig>       subscriptions      = new ArrayList<>();

    // | ================= Runtime state ================= |

    private MqttClient mqttClient;

    // Pending response futures keyed by correlationId, resolved when the device sends an ack
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingResponses =
            new ConcurrentHashMap<>();

    private final CommandTranslationEngine translationEngine = new CommandTranslationEngine();

    /**
     * Holds the topic and forwarding configuration for a single MQTT subscription.
     *
     * @param topic           MQTT topic to subscribe to
     * @param forwardToBackend Whether inbound messages on this topic should be forwarded upstream
     */
    private record MqttSubscriptionConfig(String topic, boolean forwardToBackend) {}

    // | ================= Lifecycle ================= |

    /**
     * Initialises the adapter from the device configuration.
     * Loads connection settings, subscriptions, and command definitions, then connects to the broker.
     *
     * @param config Device configuration containing broker URL, subscriptions, and commands
     * @throws EdgeControlException If the config is invalid or the broker connection fails
     */
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

    /**
     * Disconnects from the broker and cancels all pending response futures.
     * Called when the device is offboarded or the adapter is rebuilt.
     */
    @Override
    public void shutdown() {
        logger.info("Shutting down MQTT adapter for device: " + gatewayDeviceId);
        try {
            if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect();
            if (mqttClient != null) mqttClient.close();
        } catch (MqttException e) {
            logger.error("Error during MQTT shutdown: " + e.getMessage());
        }
        // Fail all pending futures so callers are not left waiting indefinitely
        pendingResponses.forEach((id, future) ->
                future.completeExceptionally(new InterruptedException("Adapter shut down")));
        pendingResponses.clear();
    }

    // | ================= Config loaders ================= |

    /**
     * Parses the 'connection' block and normalises the broker URL to Paho-compatible schemes.
     * mqtt:// is mapped to tcp://, mqtts:// is mapped to ssl://.
     *
     * @param root Root JSON config object
     * @throws CorruptedConfiguration If the 'connection' section is missing
     */
    private void loadConnection(JSONObject root) throws CorruptedConfiguration {
        if (!root.has("connection")) {
            throw new CorruptedConfiguration(
                    "MQTT adapter for device " + gatewayDeviceId
                            + " is missing required 'connection' section");
        }
        JSONObject conn = root.getJSONObject("connection");

        this.brokerUrl = conn.optString("brokerUrl", "tcp://mosquitto:1883");

        // Detect TLS before normalisation so the original scheme is still visible
        this.useTls = brokerUrl.toLowerCase().startsWith("mqtts://")
                || brokerUrl.toLowerCase().startsWith("ssl://");

        // Paho only accepts tcp:// and ssl:// — normalise user-friendly schemes
        if (brokerUrl.toLowerCase().startsWith("mqtts://")) {
            this.brokerUrl = "ssl://" + brokerUrl.substring("mqtts://".length());
        } else if (brokerUrl.toLowerCase().startsWith("mqtt://")) {
            this.brokerUrl = "tcp://" + brokerUrl.substring("mqtt://".length());
        }

        this.qos               = conn.optInt("qos",               1);
        this.keepAliveInterval = conn.optInt("keepAliveInterval", 30);
        this.connectionTimeout = conn.optInt("connectionTimeout", 10);
        this.cleanSession      = conn.optBoolean("cleanSession",  true);
        this.reconnectDelay    = conn.optInt("reconnectDelay",    5);

        logger.debug("Connection config loaded: broker=" + brokerUrl
                + " tls=" + useTls + " qos=" + qos + " keepAlive=" + keepAliveInterval + "s");
    }

    /**
     * Parses the optional 'subscriptions' array.
     * If absent, the adapter operates in command-only mode with no inbound topics.
     *
     * @param root Root JSON config object
     * @throws CorruptedConfiguration If a subscription entry is missing its 'topic' field
     */
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

    /**
     * Parses the 'commands' object and compiles each command definition.
     *
     * @param root Root JSON config object
     * @throws CorruptedConfiguration If the 'commands' section is missing or a command is invalid
     */
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

    // | ================= Broker connection ================= |

    /**
     * Creates the Paho MqttClient, configures connection options, and connects to the broker.
     * If TLS is enabled, an SSLSocketFactory is built from the APISIX certificate.
     *
     * MqttCallbackExtended is used instead of MqttCallback so that connectComplete() fires
     * on every reconnect, allowing subscriptions to be restored after a clean-session disconnect.
     *
     * @throws EdgeControlException If the connection fails or the TLS setup fails
     */
    private void connectToBroker() throws EdgeControlException {
        String clientId = "edge-control-" + gatewayDeviceId + "-" + UUID.randomUUID();

        try {
            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(cleanSession);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(connectionTimeout);
            options.setKeepAliveInterval(keepAliveInterval);
            options.setMaxReconnectDelay(reconnectDelay * 1000); // ms

            if (useTls) {
                try {
                    options.setSocketFactory(TlsHelper.buildSslSocketFactory(APISIX_CERT_PATH));
                    logger.info("MQTTS enabled for device " + gatewayDeviceId
                            + " using cert: " + APISIX_CERT_PATH);
                } catch (Exception e) {
                    throw new EdgeControlException(
                            "Failed to build SSL socket factory for device " + gatewayDeviceId
                                    + ": " + e.getMessage());
                }
            }

            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    logger.info("MQTT " + (reconnect ? "re" : "") + "connected to "
                            + serverURI + " for device " + gatewayDeviceId);
                    // Re-subscribe on every connect since cleanSession=true wipes subscriptions
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
                    // Publish ACK, nothing to do here
                }
            });

            mqttClient.connect(options);

        } catch (MqttException e) {
            throw new EdgeControlException(
                    "Failed to connect MQTT adapter for device " + gatewayDeviceId
                            + ": " + e.getMessage() + " (reason code: " + e.getReasonCode() + ")");
        }
    }

    /**
     * Subscribes to all declared topics at the configured QoS level.
     * Called from connectComplete() so it runs on both initial connect and every reconnect.
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

    // | ================= Request handling ================= |

    /**
     * Handles an inbound HTTP command request by translating it into an MQTT message
     * and publishing it to the device's command topic.
     *
     * If the command has a responseTopic, a CompletableFuture is registered to wait for
     * the device's ack, keyed by a unique correlationId embedded in the envelope.
     * If no responseTopic is configured, the command is fire-and-forget (202 returned immediately).
     *
     * If the broker is unreachable or the device times out, onDeviceUnreachable is called
     * so the queuing layer can handle the retry.
     *
     * @param request  Incoming HTTP request containing the command and params
     * @param response HTTP response to populate with the device's reply
     * @param callback Callback to signal success or device unreachability
     * @throws Exception If the request body is malformed or the command is unknown
     */
    @Override
    public void handleRequest(HttpRequest request, HttpResponse response,
                              AdapterCallback callback) throws Exception {

        if (!mqttClient.isConnected()) {
            logger.debug("MQTT broker not connected for device " + gatewayDeviceId);
            callback.onDeviceUnreachable("MQTT broker not connected for device " + gatewayDeviceId);
            return;
        }

        if (request.getBody() == null || request.getBody().isEmpty()) {
            response.setStatusCode(400);
            response.setBody("{\"error\":\"Empty request body\"}");
            callback.onSuccess();
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

        int reqHash = request.hashCode();
        Instant translateStart = Instant.now();
        logger.time("Mqtt Adapter: request to be translated (" + reqHash + ")");

        JsonNode finalPayload = translationEngine.translate(commandDefinition, backendRequest);

        Instant translateEnd = Instant.now();
        logger.time("Mqtt Adapter: request translated - time took:"
                + (translateEnd.toEpochMilli() - translateStart.toEpochMilli()) + "ms (" + reqHash + ")");
        logger.time("Mqtt Adapter: request to be sent (" + reqHash + ")");

        // Build the MQTT envelope with a correlationId so the device can echo it back in its ack
        String correlationId = UUID.randomUUID().toString();
        JSONObject envelope = new JSONObject();
        envelope.put("deviceId",      gatewayDeviceId);
        envelope.put("timestamp",     Instant.now().toString());
        envelope.put("type",          "command");
        envelope.put("correlationId", correlationId);
        if (commandDefinition.hasResponseTopic()) {
            // Tell the device which topic to publish its ack on
            envelope.put("responseTopic", commandDefinition.getResponseTopic());
        }
        envelope.put("payload", new JSONObject(finalPayload.toString()));

        // Register the future before publishing to avoid a race where the device responds
        // before we start listening
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
            callback.onSuccess();
            return;
        }

        // Fire-and-forget: no ack expected, return immediately
        if (!commandDefinition.hasResponseTopic()) {
            response.setStatusCode(202);
            response.setBody("{\"status\":\"sent\",\"correlationId\":\"" + correlationId + "\"}");
            response.setHeader("MODIFIED-BY", "EdgeControl/MQTT-Translation");
            callback.onSuccess();
            return;
        }

        // Wait for the device ack asynchronously, with a timeout
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
                        Instant end = Instant.now();
                        logger.time("Mqtt Adapter: request transmitted and res received - time took:"
                                + (end.toEpochMilli() - translateEnd.toEpochMilli()) + "ms (" + reqHash + ")");
                        callback.onSuccess();
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    pendingResponses.remove(correlationId);

                    if (cause instanceof TimeoutException) {
                        // Device did not respond in time, signal the queuing layer
                        logger.debug("Device " + gatewayDeviceId + " did not respond within "
                                + timeout.getSeconds() + "s [correlationId=" + correlationId + "]");
                        callback.onDeviceUnreachable(
                                "Device did not respond within " + timeout.getSeconds() + "s");
                    } else {
                        String msg = cause.getMessage() != null
                                ? cause.getMessage() : cause.getClass().getSimpleName();
                        logger.error("MQTT async error: " + msg);
                        try {
                            response.setStatusCode(502);
                            response.setBody("{\"error\":\"" + msg + "\"}");
                            response.setHeader("MODIFIED-BY", "EdgeControl/MQTT-Translation");
                        } catch (Exception e) {
                            logger.error("Error setting error response: " + e.getMessage());
                        } finally {
                            callback.onSuccess();
                        }
                    }
                    return null;
                });
    }

    // | ================= Inbound message routing ================= |

    /**
     * Routes an inbound MQTT message to the appropriate handler based on the declared subscriptions.
     * Messages on undeclared topics are logged and ignored.
     *
     * @param topic   MQTT topic the message arrived on
     * @param payload Message payload as a UTF-8 string
     */
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
     * Handles an inbound message from a subscribed topic.
     *
     * If the message carries a correlationId matching a pending request, the corresponding
     * future is resolved and the waiting handleRequest() call unblocks.
     * Otherwise, the message is treated as unsolicited telemetry. If the subscription has
     * forwardToBackend=true, the message is forwarded to the backendForward route with
     * the device's apikey extracted from the envelope.
     *
     * @param payload Raw message payload
     * @param sub     Subscription config for the topic this message arrived on
     */
    private void handleInboundMessage(String payload, MqttSubscriptionConfig sub) {
        try {
            JSONObject json = new JSONObject(payload);

            // Check if this is an ack for a pending command
            if (json.has("correlationId")) {
                String correlationId = json.getString("correlationId");
                CompletableFuture<String> future = pendingResponses.get(correlationId);
                if (future != null) {
                    future.complete(payload);
                    return;
                }
            }

            // Unsolicited message (telemetry, status, etc.)
            logger.info("Inbound message on " + sub.topic() + " for device "
                    + gatewayDeviceId + ": " + payload);

            if (!sub.forwardToBackend()) return;

            // The device embeds its apikey in the envelope for the gateway to use when forwarding
            String apiKey = json.optString("apikey", null);
            if (apiKey == null || apiKey.isBlank()) {
                logger.error("Cannot forward message from device " + gatewayDeviceId
                        + " - missing 'apikey' field in MQTT envelope");
                return;
            }

            // Strip apikey from the forwarded body since it belongs in the header only
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
                            "Forwarded to backendForward - status=" + result.statusCode()))
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