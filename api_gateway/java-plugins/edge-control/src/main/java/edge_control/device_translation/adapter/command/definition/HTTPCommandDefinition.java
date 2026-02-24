package edge_control.device_translation.adapter.command.definition;

import org.json.JSONObject;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public class HTTPCommandDefinition {

    private String name;
    private String endpoint;
    private String method;
    private JsonNode payloadTemplate;
    private Map<String, String> mappings;

    public HTTPCommandDefinition(JSONObject requestBody) {
        this.name = requestBody.getString("name");
        this.endpoint = requestBody.getString("endpoint");
        this.method = requestBody.getString("method");
    }

    public String getName() { return name; }
    public String getEndpoint() { return endpoint; }
    public String getMethod() { return method; }
    public JsonNode getPayloadTemplate() { return payloadTemplate; }
    public Map<String, String> getMappings() { return mappings; }
}
