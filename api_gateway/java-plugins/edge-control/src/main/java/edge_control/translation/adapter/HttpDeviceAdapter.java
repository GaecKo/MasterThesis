package edge_control.translation.adapter;

import edge_control.exceptions.EdgeControlException;
import edge_control.exceptions.IllegalOperation;
import edge_control.translation.adapter.command.definition.CommandDefinitionRegistry;
import edge_control.translation.adapter.command.definition.HTTPCommandDefinition;
import edge_control.translation.adapter.command.engine.CommandTranslationEngine;
import edge_control.translation.config.DeviceConfig;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.logger.EdgeControlLogger;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.json.JSONObject;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HttpDeviceAdapter implements DeviceAdapter, CommandDefinitionRegistry {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CommandTranslationEngine translationEngine = new CommandTranslationEngine();
    private final Map<String, HTTPCommandDefinition> commandDefinitions = new HashMap<>();

    private String gatewayDeviceId;

    @Override
    public void init(DeviceConfig config) throws EdgeControlException {
        this.gatewayDeviceId = config.getDeviceId();
        JSONObject root = config.getConfig();

        if (!root.has("commands")) {
            throw new CorruptedConfiguration(
                    "Configuration for device "
                            + gatewayDeviceId
                            + " is missing required 'commands' section."
            );
        }

        JSONObject commands = root.getJSONObject("commands");
        Iterator<String> commandNames = commands.keys();

        while (commandNames.hasNext()) {
            String commandName = commandNames.next();
            JSONObject commandJson = commands.getJSONObject(commandName);
            HTTPCommandDefinition definition = new HTTPCommandDefinition(commandName, commandJson);
            logger.debug("Added command: " + commandName);
            commandDefinitions.put(commandName, definition);
        }

        logger.info("Loaded "
                + commandDefinitions.size()
                + " command definitions for device "
                + gatewayDeviceId);
    }

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response,
                              Runnable callback) throws Exception {

        if (request.getBody() == null || request.getBody().isEmpty()) {
            response.setStatusCode(400);
            response.setBody("{\"error\":\"Empty request body\"}");
            callback.run(); // Continue chain even on error
            return;
        }

        JsonNode backendRequest = MAPPER.readTree(request.getBody());

        if (backendRequest.get("command") == null || backendRequest.get("command").isNull()) {
            throw new CorruptedConfiguration("Missing 'command' field in request body...");
        }

        HTTPCommandDefinition commandDefinition = commandDefinitions.get(backendRequest.get("command").stringValue());

        if (commandDefinition == null) {
            throw new IllegalOperation("Unknown command: " + backendRequest.get("command"));
        }

        JsonNode finalPayload = translationEngine.translate(commandDefinition, backendRequest);

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
                        callback.run();
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    try {
                        logger.error("Async request failed: " + cause.getMessage());
                        response.setStatusCode(502); // 502 Bad Gateway - upstream device failed
                        response.setBody("{\"error\":\"" + cause.getMessage() + "\"}");
                        response.setHeader("MODIFIED-BY", "EdgeControl/Protocol-Translation");
                    } catch (Exception e) {
                        logger.error("Error setting error response: " + e.getMessage());
                    } finally {
                        callback.run();
                    }
                    return null;
                });
    }

    @Override
    public HTTPCommandDefinition get(String commandName) {
        return commandDefinitions.get(commandName);
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down HTTP adapter for device: " + gatewayDeviceId);
        commandDefinitions.clear();
    }
}