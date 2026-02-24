package edge_control.device_translation.adapter.command.definition;

import edge_control.exceptions.CorruptedConfiguration;
import org.json.JSONObject;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HTTPCommandDefinition {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String name;
    private final String endpoint;
    private final String method;
    private final JsonNode payloadTemplate;
    private final Map<String, String> mappings;

    public HTTPCommandDefinition(JSONObject json) throws CorruptedConfiguration {

        this.name = requireString(json, "name");
        this.endpoint = requireString(json, "endpoint");
        this.method = requireString(json, "method");

        if (!json.has("payloadTemplate")) {
            throw new CorruptedConfiguration("Missing payloadTemplate for command: " + name);
        }

        JSONObject templateObject = json.getJSONObject("payloadTemplate");
        this.payloadTemplate = mapper.readTree(templateObject.toString());

        if (!json.has("mappings")) {
            throw new CorruptedConfiguration("Missing mappings for command: " + name);
        }

        JSONObject mappingsObject = json.getJSONObject("mappings");
        this.mappings = parseMappings(mappingsObject);
    }

    private String requireString(JSONObject obj, String key) throws CorruptedConfiguration {
        if (!obj.has(key)) {
            throw new CorruptedConfiguration("Missing required field: '" + key + "' in command configuration file");
        }
        return obj.getString(key);
    }

    private Map<String, String> parseMappings(JSONObject mappingsObject) {
        Map<String, String> result = new HashMap<>();
        Iterator<String> keys = mappingsObject.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, mappingsObject.getString(key));
        }

        return result;
    }

    public String getName() { return name; }
    public String getEndpoint() { return endpoint; }
    public String getMethod() { return method; }
    public JsonNode getPayloadTemplate() { return payloadTemplate; }
    public Map<String, String> getMappings() { return mappings; }
}