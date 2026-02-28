package edge_control.translation.adapter.command.definition;

import edge_control.translation.adapter.command.engine.path.CompiledPath;
import edge_control.translation.adapter.command.engine.path.PathCompiler;

import org.json.JSONObject;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HTTPCommandDefinition implements CommandDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------
    // Immutable Fields
    // ------------------------------------------------------------

    private final String name;
    private final String endpoint;
    private final String method;

    private final JsonNode payloadTemplate;

    private final Map<String, CompiledPath> compiledMappings;

    // ------------------------------------------------------------
    // Constructor (compile everything here)
    // ------------------------------------------------------------

    public HTTPCommandDefinition(JSONObject commandJson) throws Exception {

        this.name = commandJson.getString("name");
        this.endpoint = commandJson.getString("endpoint");
        this.method = commandJson.getString("method");

        // ---- Payload template ----
        JSONObject payloadJson = commandJson.getJSONObject("payloadTemplate");
        this.payloadTemplate =
                MAPPER.readTree(payloadJson.toString());

        // ---- Compile mappings ----
        JSONObject mappingsJson = commandJson.getJSONObject("mappings");

        Map<String, CompiledPath> compiled = new HashMap<>();
        Iterator<String> keys = mappingsJson.keys();

        while (keys.hasNext()) {
            String paramName = keys.next();
            String path = mappingsJson.getString(paramName);

            compiled.put(paramName, PathCompiler.compile(path));
        }

        this.compiledMappings = Collections.unmodifiableMap(compiled);

        // ---- Cleanup policy ----
        JSONObject cleanupJson = commandJson.optJSONObject("cleanup");
    }

    // ------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------

    public String getName() {
        return name;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getMethod() {
        return method;
    }

    /**
     * Returns a deep copy of the payload template.
     * This is critical to avoid modifying the base template.
     */
    public JsonNode createPayloadInstance() {
        return payloadTemplate.deepCopy();
    }

    public CompiledPath getCompiledPath(String paramName) {
        return compiledMappings.get(paramName);
    }

    public Map<String, CompiledPath> getCompiledMappings() {
        return compiledMappings;
    }
}