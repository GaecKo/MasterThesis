package edge_control.device_translation.adapter;

import edge_control.device_translation.adapter.command.definition.HTTPCommandDefinition;
import edge_control.device_translation.config.DeviceConfig;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.logger.EdgeControlLogger;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.json.JSONObject;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


public class HttpDeviceAdapter implements DeviceAdapter {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private String gatewayDeviceId;
    private final Map<String, HTTPCommandDefinition> commandDefinitions = new HashMap<>();

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void init(DeviceConfig config) throws Exception {

        // TODO: extract some logic to a command translator, + better opti!! 

        logger.info("Initializing HTTP adapter for device: " +
                config.getDeviceId() +
                " fingerprint: " +
                config.fingerprint());

        JSONObject root = config.getConfig();

        this.gatewayDeviceId = config.getDeviceId();

        // commands parsing
        if (!root.has("commands")) {
            throw new CorruptedConfiguration("Configuration file for device " + gatewayDeviceId + " misses commands key.");
        }

        JSONObject commands = root.getJSONObject("commands");

        // iterate through all commands
        Iterator<String> keys = commands.keys();
        while (keys.hasNext()) {

            String commandName = keys.next();
            JSONObject commandJson = commands.getJSONObject(commandName);
            // load command
            HTTPCommandDefinition definition =
                    new HTTPCommandDefinition(commandJson);

            commandDefinitions.put(commandName, definition);
        }

        logger.info("Loaded " + commandDefinitions.size() +
                " command definitions for device: " + gatewayDeviceId);
    }

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response) throws Exception {

        // Parse backend request
        JsonNode backendRequest = mapper.readTree(request.getBody());

        String commandName = backendRequest.get("command").asText();
        JsonNode params = backendRequest.get("params");

        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("Missing or invalid 'params'");
        }

        HTTPCommandDefinition definition = commandDefinitions.get(commandName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown command: " + commandName);
        }

        // Clone payload template
        ObjectNode finalPayload = (ObjectNode) definition
                .getPayloadTemplate()
                .deepCopy();

        // Apply mappings
        for (Map.Entry<String, String> entry : definition.getMappings().entrySet()) {

            String paramName = entry.getKey();
            String targetPath = entry.getValue();

            JsonNode value = params.get(paramName);

            if (value != null) {
                setValueByPath(finalPayload, targetPath, value);
            }
        }

        logger.info("Final structured JSON:");
        logger.info(finalPayload.toPrettyString());

        response.setBody(mapper.writeValueAsString(finalPayload));
        response.setStatusCode(200);

        // TODO: cleanup
    }


    private void setValueByPath(ObjectNode root, String path, JsonNode value) {

        String[] parts = path.split("\\.");
        JsonNode current = root;

        for (int i = 0; i < parts.length; i++) {

            String part = parts[i];

            boolean isLast = (i == parts.length - 1);

            // Check if part contains array index: field[0]
            if (part.contains("[") && part.contains("]")) {

                String fieldName = part.substring(0, part.indexOf("["));
                int index = Integer.parseInt(
                        part.substring(part.indexOf("[") + 1, part.indexOf("]"))
                );

                // Ensure array exists
                JsonNode arrayNode = current.get(fieldName);
                if (arrayNode == null || !arrayNode.isArray()) {
                    arrayNode = mapper.createArrayNode();
                    ((ObjectNode) current).set(fieldName, arrayNode);
                }

                // Expand array if needed
                while (arrayNode.size() <= index) {
                    ((tools.jackson.databind.node.ArrayNode) arrayNode)
                            .add(mapper.createObjectNode());
                }

                if (isLast) {
                    ((tools.jackson.databind.node.ArrayNode) arrayNode)
                            .set(index, value);
                    return;
                } else {
                    current = arrayNode.get(index);
                }

            } else {
                // Normal object field

                if (isLast) {
                    ((ObjectNode) current).set(part, value);
                    return;
                }

                JsonNode next = current.get(part);

                if (!(next instanceof ObjectNode)) {
                    next = mapper.createObjectNode();
                    ((ObjectNode) current).set(part, next);
                }

                current = next;
            }
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down HTTP adapter for device: " + gatewayDeviceId);
        commandDefinitions.clear();
    }
}