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

public class HTTPCommandDefinition implements CommandDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String endpoint;
    private final String method;

    private final boolean removeNulls;
    private final boolean removeEmpty;
    private final boolean emptyObjectToNull;

    private final Duration connectTimeout;
    private final Duration requestTimeout;

    private final JsonNode payloadTemplate;

    private final Map<String, CompiledPath> compiledMappings;

    public HTTPCommandDefinition(String commandName, JSONObject commandJson) throws CorruptedConfiguration {

        // ---- Name ----
        this.name = commandJson.optString("name", null);
        if (name == null || name.isEmpty()) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'name' field or is empty");
        }

        // ---- Endpoint ----
        this.endpoint = commandJson.optString("endpoint", null);
        if (endpoint == null || endpoint.isEmpty()) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'endpoint' field or is empty");
        } else if (!isValidURL(endpoint)) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " has invalid endpoint: " + endpoint);
        }

        // ---- Method ----
        this.method = commandJson.optString("method", null);
        if (method == null || method.isEmpty()) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'method' field or is empty");
        } else if (!isValidMethod(method)) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " has invalid method: " + method +
                    ". Valid values: [GET, HEAD, POST, PUT, DELETE, MKCOL, COPY, MOVE, OPTIONS, PROPFIND, PROPPATCH, LOCK, UNLOCK, PATCH, TRACE]");

        }

        // ---- Payload template ----
        if (!commandJson.has("payloadTemplate")) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'payloadTemplate' field");
        }
        JSONObject payloadJson = commandJson.getJSONObject("payloadTemplate");
        // payload template can be empty
        try {
            this.payloadTemplate = MAPPER.readTree(payloadJson.toString());
        } catch (Exception e) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " has invalid 'payloadTemplate': " + e.getMessage());
        }

        // ---- Compile mappings ----
        if (!commandJson.has("mappings")) {
            throw new CorruptedConfiguration("HTTP command: " + commandName + " misses 'mappings' field");
        }
        JSONObject mappingsJson = commandJson.getJSONObject("mappings");
        // mappings can be empty

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
                throw new CorruptedConfiguration("HTTP command: " + commandName + " mapping '" + paramName + "' has invalid JsonPath '" + path + "': " + e.getMessage());
            }
        }
        this.compiledMappings = Collections.unmodifiableMap(compiled);

        // ---- Cleanup policy ----
        JSONObject cleanupJson = commandJson.optJSONObject("cleanup");
        if (cleanupJson != null) {
            this.removeNulls = cleanupJson.optBoolean("removeNulls", false);
            this.removeEmpty = cleanupJson.optBoolean("removeEmpty", false);
            this.emptyObjectToNull = cleanupJson.optBoolean("emptyObjectToNull", false);
        } else {
            // Defaults when cleanup block is absent entirely
            this.removeNulls = false;
            this.removeEmpty = false;
            this.emptyObjectToNull = false;
        }

        // ---- timeouts ----
        JSONObject timeoutsJson = commandJson.optJSONObject("timeouts");
        if (cleanupJson != null) {
            this.connectTimeout = Duration.ofSeconds(timeoutsJson.optInt("connect", 0));
            this.requestTimeout = Duration.ofSeconds(timeoutsJson.optInt("request", 0));

        } else {
            // Defaults when cleanup block is absent entirely
            this.connectTimeout = Duration.ofSeconds(0);
            this.requestTimeout = Duration.ofSeconds(0);
        }

    }
    boolean isValidURL(String url)  {
        try {
            new URL(url).toURI();
            return true;
        } catch (MalformedURLException | URISyntaxException e) {
            return false;
        }
    }

    boolean isValidMethod(String method) {
        try {
            HttpRequest.Method.valueOf(method);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Getters

    public String getName() {
        return name;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getMethod() {
        return method;
    }

    // cleanup:
    @Override
    public boolean removeNulls() {
        return removeNulls;
    }

    @Override
    public boolean removeEmpty() {
        return removeEmpty;
    }

    @Override
    public boolean emptyObjectToNull() {
        return emptyObjectToNull;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

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