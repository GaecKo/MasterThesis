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

    public JsonNode translate(JsonNode backendRequest,
                              CommandDefinitionRegistry registry)
            throws Exception {

        // Retrieve command name and its corresponding definition
        String commandName = backendRequest.get("command").stringValue();
        CommandDefinition definition = registry.get(commandName);

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

        return result;
    }
}