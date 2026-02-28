package edge_control.translation.adapter;

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
    public void init(DeviceConfig config) throws Exception {

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

            HTTPCommandDefinition definition =
                    new HTTPCommandDefinition(commandJson);
            logger.debug("Added command: " + commandName);
            commandDefinitions.put(commandName, definition);
        }

        logger.info("Loaded "
                + commandDefinitions.size()
                + " command definitions for device "
                + gatewayDeviceId);
    }

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response) throws Exception {

        if (request.getBody() == null || request.getBody().isEmpty()) {
            response.setStatusCode(400);
            response.setBody("{\"error\":\"Empty request body\"}");
            return;
        }

        JsonNode backendRequest = MAPPER.readTree(request.getBody());

        JsonNode finalPayload =
                translationEngine.translate(backendRequest, this);

        logger.debug("Translated payload for device "
                + gatewayDeviceId
                + ":\n"
                + finalPayload.toPrettyString());

        response.setStatusCode(200);
        response.setBody(MAPPER.writeValueAsString(finalPayload));
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