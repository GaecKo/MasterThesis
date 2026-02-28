package edge_control.translation.adapter.command.definition;

import edge_control.translation.adapter.command.engine.path.CompiledPath;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public interface CommandDefinition {

    String getName();

    JsonNode createPayloadInstance();

    Map<String, CompiledPath> getCompiledMappings();
}