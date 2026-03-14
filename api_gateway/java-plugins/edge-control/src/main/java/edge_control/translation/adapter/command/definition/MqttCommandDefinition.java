package edge_control.translation.adapter.command.definition;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.translation.adapter.command.engine.path.CompiledPath;
import edge_control.translation.adapter.command.engine.path.PathCompiler;

import org.json.JSONObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;

public class MqttCommandDefinition implements CommandDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String topic;

    // null means fire-and-forget — adapter won't register a pending future
    private final String responseTopic;

    private final Duration responseTimeout;

    private final boolean removeNulls;
    private final boolean removeEmpty;
    private final boolean emptyObjectToNull;

    private final JsonNode payloadTemplate;

    private final Map<String, CompiledPath> compiledMappings;

    public MqttCommandDefinition(String commandName, JSONObject commandJson) throws CorruptedConfiguration {

        // ---- Name ----
        this.name = commandJson.optString("name", null);
        if (name == null || name.isEmpty()) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " misses 'name' field or is empty");
        }

        // ---- Topic ----
        this.topic = commandJson.optString("topic", null);
        if (topic == null || topic.isEmpty()) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " misses 'topic' field or is empty");
        }

        // ---- Response topic (optional — null = fire-and-forget) ----
        String rawResponseTopic = commandJson.optString("responseTopic", null);
        this.responseTopic = (rawResponseTopic == null || rawResponseTopic.isEmpty())
                ? null : rawResponseTopic;

        // ---- Timeouts ----
        JSONObject timeoutsJson = commandJson.optJSONObject("timeouts");
        if (timeoutsJson != null) {
            this.responseTimeout = Duration.ofSeconds(timeoutsJson.optInt("response", 10));
        } else {
            this.responseTimeout = Duration.ofSeconds(10);
        }

        // ---- Payload template ----
        if (!commandJson.has("payloadTemplate")) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " misses 'payloadTemplate' field");
        }
        JSONObject payloadJson = commandJson.getJSONObject("payloadTemplate");
        try {
            this.payloadTemplate = MAPPER.readTree(payloadJson.toString());
        } catch (Exception e) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " has invalid 'payloadTemplate': " + e.getMessage());
        }

        // ---- Compile mappings ----
        if (!commandJson.has("mappings")) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " misses 'mappings' field");
        }
        JSONObject mappingsJson = commandJson.getJSONObject("mappings");

        Map<String, CompiledPath> compiled = new HashMap<>();
        Iterator<String> keys = mappingsJson.keys();
        while (keys.hasNext()) {
            String paramName = keys.next();
            String path = mappingsJson.optString(paramName, null);

            if (path == null || path.isEmpty()) {
                throw new CorruptedConfiguration(
                        "MQTT command: " + commandName + " mapping '" + paramName
                                + "' has a null or empty path");
            }
            try {
                compiled.put(paramName, PathCompiler.compile(path));
            } catch (Exception e) {
                throw new CorruptedConfiguration(
                        "MQTT command: " + commandName + " mapping '" + paramName
                                + "' has invalid path '" + path + "': " + e.getMessage());
            }
        }
        this.compiledMappings = Collections.unmodifiableMap(compiled);

        // ---- Cleanup policy ----
        JSONObject cleanupJson = commandJson.optJSONObject("cleanup");
        if (cleanupJson != null) {
            this.removeNulls       = cleanupJson.optBoolean("removeNulls",       false);
            this.removeEmpty       = cleanupJson.optBoolean("removeEmpty",        false);
            this.emptyObjectToNull = cleanupJson.optBoolean("emptyObjectToNull",  false);
        } else {
            this.removeNulls       = false;
            this.removeEmpty       = false;
            this.emptyObjectToNull = false;
        }
    }

    // ── CommandDefinition interface ───────────────────────────────────────────

    @Override
    public String getName() { return name; }

    @Override
    public JsonNode createPayloadInstance() { return payloadTemplate.deepCopy(); }

    @Override
    public Map<String, CompiledPath> getCompiledMappings() { return compiledMappings; }

    @Override
    public boolean removeNulls() { return removeNulls; }

    @Override
    public boolean removeEmpty() { return removeEmpty; }

    @Override
    public boolean emptyObjectToNull() { return emptyObjectToNull; }

    // ── MQTT-specific getters ─────────────────────────────────────────────────

    public String getTopic() { return topic; }

    /** Null means fire-and-forget — no response future will be registered. */
    public String getResponseTopic() { return responseTopic; }

    public boolean hasResponseTopic() { return responseTopic != null; }

    public Duration getResponseTimeout() { return responseTimeout; }

    public CompiledPath getCompiledPath(String paramName) {
        return compiledMappings.get(paramName);
    }
}