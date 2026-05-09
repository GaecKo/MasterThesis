package edge_control.translation.adapter.command.definition;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.translation.adapter.command.engine.path.CompiledPath;
import edge_control.translation.adapter.command.engine.path.PathCompiler;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.json.JSONObject;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.*;

/**
 * Parsed and compiled definition for a single HTTP command.
 * Validates and stores the endpoint, method, payload template, field mappings,
 * cleanup policy, and timeouts from the device config at load time.
 */
public class HttpCommandDefinition implements CommandDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String endpoint;
    private final String method;

    private final boolean removeNulls;
    private final boolean removeEmpty;
    private final boolean emptyObjectToNull;

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final String security;

    private final JsonNode payloadTemplate;
    private final Map<String, CompiledPath> compiledMappings;

    /**
     * Parses and validates a single command entry from the device config.
     *
     * @param commandName Key used in the commands map, included in error messages
     * @param commandJson JSON object for this command
     * @throws CorruptedConfiguration If any required field is missing, invalid, or unparseable
     */
    public HttpCommandDefinition(String commandName, JSONObject commandJson) throws CorruptedConfiguration {

        // Name
        this.name = commandJson.optString("name", null);
        if (name == null || name.isEmpty()) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'name' field or is empty");
        }

        // Endpoint — must be present and a valid URL
        this.endpoint = commandJson.optString("endpoint", null);
        if (endpoint == null || endpoint.isEmpty()) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'endpoint' field or is empty");
        } else if (!isValidURL(endpoint)) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " has invalid endpoint: " + endpoint);
        }

        // Method — must be a recognised HTTP verb
        this.method = commandJson.optString("method", null);
        if (method == null || method.isEmpty()) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'method' field or is empty");
        } else if (!isValidMethod(method)) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " has invalid method: " + method +
                    ". Valid values: [GET, HEAD, POST, PUT, DELETE, MKCOL, COPY, MOVE, OPTIONS, PROPFIND, PROPPATCH, LOCK, UNLOCK, PATCH, TRACE]");
        }

        // Payload template — required but may be an empty object
        if (!commandJson.has("payloadTemplate")) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'payloadTemplate' field");
        }
        try {
            this.payloadTemplate = MAPPER.readTree(commandJson.getJSONObject("payloadTemplate").toString());
        } catch (Exception e) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " has invalid 'payloadTemplate': " + e.getMessage());
        }

        // Mappings — required but may be empty; each path is compiled eagerly for performance
        if (!commandJson.has("mappings")) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'mappings' field");
        }
        JSONObject mappingsJson = commandJson.getJSONObject("mappings");
        Map<String, CompiledPath> compiled = new HashMap<>();
        Iterator<String> keys = mappingsJson.keys();
        while (keys.hasNext()) {
            String paramName = keys.next();
            String path = mappingsJson.optString(paramName, null);
            if (path == null || path.isEmpty()) {
                throw new CorruptedConfiguration("HTTP command: " + commandName + " mapping '" + paramName + "' has a null or empty path");
            }
            try {
                compiled.put(paramName, PathCompiler.compile(path));
            } catch (Exception e) {
                throw new CorruptedConfiguration("HTTP command: " + commandName + " mapping '" + paramName + "' has invalid path '" + path + "': " + e.getMessage());
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

        // Timeouts — default to 0 (no timeout) if the block is absent
        JSONObject timeoutsJson = commandJson.optJSONObject("timeouts");
        if (timeoutsJson != null) {
            this.connectTimeout = Duration.ofSeconds(timeoutsJson.optInt("connect", 0));
            this.requestTimeout = Duration.ofSeconds(timeoutsJson.optInt("request", 0));
        } else {
            this.connectTimeout = Duration.ofSeconds(0);
            this.requestTimeout = Duration.ofSeconds(0);
        }

        this.security = commandJson.optString("security", null);
    }

    // | ================= Validation helpers ================= |

    /**
     * @return True if the string is a well-formed URL with a valid URI syntax
     */
    boolean isValidURL(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (MalformedURLException | URISyntaxException e) {
            return false;
        }
    }

    /**
     * @return True if the string matches a recognised HTTP method name
     */
    boolean isValidMethod(String method) {
        try {
            HttpRequest.Method.valueOf(method);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // | ================= Getters ================= |

    @Override public String getName()            { return name; }
    public String getEndpoint()                  { return endpoint; }
    public String getMethod()                    { return method; }
    @Override public boolean removeNulls()       { return removeNulls; }
    @Override public boolean removeEmpty()       { return removeEmpty; }
    @Override public boolean emptyObjectToNull() { return emptyObjectToNull; }
    @Override public String getSecurity()        { return security; }
    public Duration getConnectTimeout()          { return connectTimeout; }
    public Duration getRequestTimeout()          { return requestTimeout; }

    /** @return A deep copy of the payload template so each translation starts fresh */
    @Override
    public JsonNode createPayloadInstance() {
        return payloadTemplate.deepCopy();
    }

    /**
     * @param paramName Param name as declared in the mappings
     * @return The compiled path for the given param, or null if not mapped
     */
    public CompiledPath getCompiledPath(String paramName) {
        return compiledMappings.get(paramName);
    }

    @Override
    public Map<String, CompiledPath> getCompiledMappings() {
        return compiledMappings;
    }
}