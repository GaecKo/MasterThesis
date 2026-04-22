package edge_control.translation.adapter.command.engine;

import edge_control.translation.adapter.command.definition.CommandDefinition;
import edge_control.translation.adapter.command.engine.path.CompiledPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Translates a backend command request into a device-specific payload.
 *
 * Takes a CommandDefinition (containing the payload template and field mappings)
 * and a backend request, maps the provided params onto the template, then applies
 * the cleanup policy before returning the final payload.
 */
public class CommandTranslationEngine {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Produces a translated payload ready to send to the device.
     *
     * @param definition    Command definition containing the template, mappings and cleanup policy
     * @param backendRequest Full backend request; must contain a 'params' object
     * @return Translated and cleaned payload
     * @throws IllegalArgumentException If 'params' is missing or not an object
     * @throws Exception If path application fails
     */
    public JsonNode translate(CommandDefinition definition, JsonNode backendRequest)
            throws Exception {

        // Extract the params block sent by the backend
        JsonNode params = backendRequest.get("params");
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("Missing or invalid 'params' in backend request");
        }

        // Start from a deep copy of the payload template so the original is never modified
        ObjectNode result = (ObjectNode) definition.createPayloadInstance();

        // Apply each mapping: locate the param value by name and write it into the
        // correct path in the result using the pre-compiled path expression
        for (Map.Entry<String, CompiledPath> entry : definition.getCompiledMappings().entrySet()) {
            JsonNode value = params.get(entry.getKey());
            if (value != null) {
                entry.getValue().apply(result, value, mapper);
            }
            // If a mapped param is absent from the request, the template default is kept
        }

        // Apply cleanup rules (remove nulls, empty strings, etc.) to the final payload
        CleanupEngine.clean(
                result,
                definition.removeNulls(),
                definition.removeEmpty(),
                definition.emptyObjectToNull()
        );

        return result;
    }
}