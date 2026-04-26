package edge_control.translation.adapter.command.definition;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.translation.adapter.command.engine.path.CompiledPath;
import edge_control.translation.adapter.command.engine.path.PathCompiler;

import org.json.JSONObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;

/**
 * Parsed and compiled definition for a single MQTT command.
 * Stores the publish topic, optional response topic, payload template,
 * field mappings, cleanup policy, and response timeout from the device config.
 */
public class MqttCommandDefinition implements CommandDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String topic;

    // Null means fire-and-forget — no pending future will be registered for this command
    private final String responseTopic;

    private final Duration responseTimeout;

    private final boolean removeNulls;
    private final boolean removeEmpty;
    private final boolean emptyObjectToNull;

    private final JsonNode payloadTemplate;
    private final Map<String, CompiledPath> compiledMappings;

    /**
     * Parses and validates a single MQTT command entry from the device config.
     *
     * @param commandName Key used in the commands map, included in error messages
     * @param commandJson JSON object for this command
     * @throws CorruptedConfiguration If any required field is missing, invalid, or unparseable
     */
    public MqttCommandDefinition(String commandName, JSONObject commandJson) throws CorruptedConfiguration {

        // Name
        this.name = commandJson.optString("name", null);
        if (name == null || name.isEmpty()) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " misses 'name' field or is empty");
        }

        // Topic — the MQTT topic the command will be published to
        this.topic = commandJson.optString("topic", null);
        if (topic == null || topic.isEmpty()) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " misses 'topic' field or is empty");
        }

        // Response topic — optional; absent or empty means fire-and-forget
        String rawResponseTopic = commandJson.optString("responseTopic", null);
        this.responseTopic = (rawResponseTopic == null || rawResponseTopic.isEmpty())
                ? null : rawResponseTopic;

        // Response timeout — defaults to 10s if the timeouts block is absent
        JSONObject timeoutsJson = commandJson.optJSONObject("timeouts");
        this.responseTimeout = (timeoutsJson != null)
                ? Duration.ofSeconds(timeoutsJson.optInt("response", 10))
                : Duration.ofSeconds(10);

        // Payload template — required but may be an empty object
        if (!commandJson.has("payloadTemplate")) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " misses 'payloadTemplate' field");
        }
        try {
            this.payloadTemplate = MAPPER.readTree(
                    commandJson.getJSONObject("payloadTemplate").toString());
        } catch (Exception e) {
            throw new CorruptedConfiguration(
                    "MQTT command: " + commandName + " has invalid 'payloadTemplate': " + e.getMessage());
        }

        // Mappings — required but may be empty; each path is compiled eagerly for performance
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
                        "MQTT command: " + commandName + " mapping '" + paramName + "' has a null or empty path");
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

        // Cleanup policy — all flags default to false if the block is absent
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

    // | ================= CommandDefinition interface ================= |

    @Override public String getName()            { return name; }
    @Override public boolean removeNulls()       { return removeNulls; }
    @Override public boolean removeEmpty()       { return removeEmpty; }
    @Override public boolean emptyObjectToNull() { return emptyObjectToNull; }

    /** @return A deep copy of the payload template so each translation starts fresh */
    @Override
    public JsonNode createPayloadInstance() { return payloadTemplate.deepCopy(); }

    @Override
    public Map<String, CompiledPath> getCompiledMappings() { return compiledMappings; }

    // | ================= MQTT-specific getters ================= |

    /** @return The MQTT topic this command is published to */
    public String getTopic() { return topic; }

    /** @return The topic the device should publish its ack on, or null for fire-and-forget */
    public String getResponseTopic() { return responseTopic; }

    /** @return True if this command expects a response from the device */
    public boolean hasResponseTopic() { return responseTopic != null; }

    /** @return Maximum time to wait for the device ack before signalling unreachable */
    public Duration getResponseTimeout() { return responseTimeout; }

    /**
     * @param paramName Param name as declared in the mappings
     * @return The compiled path for the given param, or null if not mapped
     */
    public CompiledPath getCompiledPath(String paramName) {
        return compiledMappings.get(paramName);
    }
}