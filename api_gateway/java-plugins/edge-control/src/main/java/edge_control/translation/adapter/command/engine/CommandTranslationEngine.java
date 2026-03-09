package edge_control.translation.adapter.command.engine;

import edge_control.translation.adapter.command.definition.CommandDefinition;
import edge_control.translation.adapter.command.definition.CommandDefinitionRegistry;
import edge_control.translation.adapter.command.engine.path.CompiledPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

public class CommandTranslationEngine {

    private static final ObjectMapper mapper = new ObjectMapper();

    public JsonNode translate(CommandDefinition definition, JsonNode backendRequest)
            throws Exception {

        String commandName = definition.getName();

        if (definition == null) {
            throw new IllegalArgumentException("Unknown command: " + commandName);
        }

        // Retrieve params given by the backend
        JsonNode params = backendRequest.get("params");

        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("Missing or invalid 'params'");
        }

        // Retrive payload template (copy)
        ObjectNode result =
                (ObjectNode) definition.createPayloadInstance();

        // for each mapping param_name -> compiled_path:
        for (Map.Entry<String, CompiledPath> entry :
                definition.getCompiledMappings().entrySet()) {
            // param value
            JsonNode value = params.get(entry.getKey());

            if (value != null) {
                // using CompiledPath, put in payload "result" the value of param
                entry.getValue().apply(result, value, mapper);
            }
        }

        // cleanup
        CleanupEngine.clean(
                result,
                definition.removeNulls(),
                definition.removeEmpty(),
                definition.emptyObjectToNull()
        );

        return result;
    }
}