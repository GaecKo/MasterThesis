package edge_control.translation.adapter;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.exceptions.IllegalOperation;
import edge_control.translation.adapter.command.definition.HttpCommandDefinition;
import edge_control.translation.adapter.command.engine.CommandTranslationEngine;
import edge_control.translation.config.DeviceConfig;
import edge_control.logger.EdgeControlLogger;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.json.JSONObject;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HttpDeviceAdapter implements DeviceAdapter {
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm:ss.SSSS")
            .withZone(ZoneId.systemDefault());

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CommandTranslationEngine translationEngine = new CommandTranslationEngine();
    private final Map<String, HttpCommandDefinition> commandDefinitions = new HashMap<>();

    private String gatewayDeviceId;

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    public void init(DeviceConfig config) throws EdgeControlException {
        this.gatewayDeviceId = config.getDeviceId();
        logger.info("Initialising HTTP adapter for device: " + gatewayDeviceId);
        loadCommands(config.getConfig());
    }

    // ── Config loaders ────────────────────────────────────────────────────────

    private void loadCommands(JSONObject root) throws CorruptedConfiguration {
        if (!root.has("commands")) {
            throw new CorruptedConfiguration(
                    "HTTP adapter for device " + gatewayDeviceId
                            + " is missing required 'commands' section");
        }

        JSONObject commands = root.getJSONObject("commands");
        Iterator<String> commandNames = commands.keys();

        while (commandNames.hasNext()) {
            String commandName = commandNames.next();
            JSONObject commandJson = commands.getJSONObject(commandName);
            HttpCommandDefinition definition = new HttpCommandDefinition(commandName, commandJson);
            commandDefinitions.put(commandName, definition);
            logger.debug("Added command: " + commandName
                    + " → " + definition.getMethod() + " " + definition.getEndpoint());
        }

        logger.info("Loaded " + commandDefinitions.size()
                + " command definitions for device " + gatewayDeviceId);
    }

    // ── Request handling ──────────────────────────────────────────────────────

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response,
                              AdapterCallback callback) throws Exception {

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
        HttpCommandDefinition commandDefinition = commandDefinitions.get(commandName);

        if (commandDefinition == null) {
            throw new IllegalOperation("Unknown command: " + commandName);
        }
        int reqHash = request.hashCode();
        Instant now = Instant.now();
        logger.time("[" + formatter.format(now) + "] Http Adapter: request to be translated (" + reqHash + ")");
        JsonNode finalPayload = translationEngine.translate(commandDefinition, backendRequest);
        Instant after = Instant.now();
        logger.time("[" + formatter.format(after) + "] Http Adapter: request translated - time took:" + (after.toEpochMilli() - now.toEpochMilli()) + "ms (" + reqHash + ")");
        logger.time("[" + formatter.format(after) + "] Http Adapter: request to be sent (" + reqHash + ")");
        HttpForgery.doRequestAsync(
                        commandDefinition.getMethod(),
                        commandDefinition.getEndpoint(),
                        finalPayload.toString(),
                        request.getHeaders(),
                        commandDefinition.getConnectTimeout(),
                        commandDefinition.getRequestTimeout())
                .thenAccept(result -> {
                    try {
                        response.setStatusCode(result.statusCode());
                        response.setBody(result.body());
                        response.setHeader("MODIFIED-BY", "EdgeControl/Protocol-Translation");
                        logger.debug("Successfully processed request for device: " + gatewayDeviceId);
                    } catch (Exception e) {
                        logger.error("Error setting response: " + e.getMessage());
                        response.setStatusCode(500);
                        response.setBody("{\"error\":\"Error processing response\"}");
                    } finally {
                        Instant end = Instant.now();
                        logger.time("[" + formatter.format(end) + "] Http Adapter: request transmitted and res received - time took:" + (end.toEpochMilli() - after.toEpochMilli()) + "ms (" + reqHash + ")");
                        callback.onSuccess();
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;

                    if (cause instanceof java.io.IOException) {
                        // Device is down — signal queuing layer, do NOT set a response
                        logger.info("Device " + gatewayDeviceId + " unreachable: "
                                + cause.getClass().getSimpleName()
                                + (cause.getMessage() != null ? " — " + cause.getMessage() : ""));
                        callback.onDeviceUnreachable(
                                cause.getClass().getSimpleName()
                                        + (cause.getMessage() != null ? " — " + cause.getMessage() : ""));
                    } else {
                        // All other errors — set response and continue chain normally
                        String msg = cause.getMessage() != null
                                ? cause.getMessage() : cause.getClass().getSimpleName();
                        logger.error("Async request failed: " + msg);
                        try {
                            response.setStatusCode(502);
                            response.setBody("{\"error\":\"" + msg + "\"}");
                            response.setHeader("MODIFIED-BY", "EdgeControl/Protocol-Translation");
                        } catch (Exception e) {
                            logger.error("Error setting error response: " + e.getMessage());
                        } finally {
                            callback.onSuccess();
                        }
                    }
                    return null;
                });
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    @Override
    public void shutdown() {
        logger.info("Shutting down HTTP adapter for device: " + gatewayDeviceId);
        commandDefinitions.clear();
    }
}