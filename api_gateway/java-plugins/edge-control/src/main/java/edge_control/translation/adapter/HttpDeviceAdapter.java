package edge_control.translation.adapter;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.exceptions.IllegalOperation;
import edge_control.translation.adapter.command.definition.HttpCommandDefinition;
import edge_control.translation.adapter.command.engine.CommandTranslationEngine;
import edge_control.translation.config.DeviceConfig;
import edge_control.logger.EdgeControlLogger;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.json.JSONObject;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * DeviceAdapter implementation for HTTP-based devices.
 *
 * Translates incoming HTTP commands using configured payload templates and mappings,
 * then forwards the translated request to the device's endpoint asynchronously.
 *
 * If the device is unreachable (IOException), onDeviceUnreachable is called so the
 * queuing layer can handle retries. All other errors result in a 502 response.
 *
 * When DEVICE_MOCKUP is true, the actual HTTP request to the device is skipped after
 * translation and a 200 is returned immediately, isolating plugin overhead from device latency.
 */
public class HttpDeviceAdapter implements DeviceAdapter {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();
    private static final ObjectMapper MAPPER      = new ObjectMapper();

    private static final DateTimeFormatter TIMING_FORMATTER =
            DateTimeFormatter.ofPattern("hh:mm:ss.SSSS").withZone(ZoneId.systemDefault());

    private final CommandTranslationEngine translationEngine  = new CommandTranslationEngine();
    private final Map<String, HttpCommandDefinition> commandDefinitions = new HashMap<>();

    private String gatewayDeviceId;

    // | ================= Lifecycle ================= |

    /**
     * Initialises the adapter by loading command definitions from the device config.
     *
     * @param config Device configuration containing the commands section
     * @throws EdgeControlException If the config is invalid or a command definition is malformed
     */
    @Override
    public void init(DeviceConfig config) throws EdgeControlException {
        this.gatewayDeviceId = config.getDeviceId();
        logger.info("Initialising HTTP adapter for device: " + gatewayDeviceId);
        loadCommands(config.getConfig());
    }

    /**
     * Clears command definitions. Called when the device is offboarded or the adapter is rebuilt.
     */
    @Override
    public void shutdown() {
        logger.info("Shutting down HTTP adapter for device: " + gatewayDeviceId);
        commandDefinitions.clear();
    }

    // | ================= Config loaders ================= |

    /**
     * Parses the 'commands' object and compiles each HTTP command definition.
     *
     * @param root Root JSON config object
     * @throws CorruptedConfiguration If the 'commands' section is missing or a definition is invalid
     */
    private void loadCommands(JSONObject root) throws CorruptedConfiguration {
        if (!root.has("commands")) {
            throw new CorruptedConfiguration(
                    "HTTP adapter for device " + gatewayDeviceId
                            + " is missing required 'commands' section");
        }

        JSONObject commands = root.getJSONObject("commands");
        Iterator<String> commandNames = commands.keys();

        while (commandNames.hasNext()) {
            String commandName = commandNames.next();
            JSONObject commandJson = commands.getJSONObject(commandName);
            HttpCommandDefinition definition = new HttpCommandDefinition(commandName, commandJson);
            commandDefinitions.put(commandName, definition);
            logger.debug("Added command: " + commandName
                    + " -> " + definition.getMethod() + " " + definition.getEndpoint());
        }

        logger.info("Loaded " + commandDefinitions.size()
                + " command definitions for device " + gatewayDeviceId);
    }

    // | ================= Request handling ================= |

    /**
     * Handles an inbound HTTP command request by translating it and forwarding it
     * to the device's endpoint asynchronously.
     *
     * In mockup mode, the request is translated but not sent —
     * a 200 is returned immediately to isolate plugin overhead from device latency.
     *
     * On success, the device's response is forwarded back as-is.
     * On IOException (device down), onDeviceUnreachable is called without setting a response,
     * allowing the queuing layer to take over.
     * On all other errors, a 502 response is returned.
     *
     * @param request  Incoming HTTP request containing the command and params
     * @param response HTTP response to populate with the device's reply
     * @param callback Callback to signal success or device unreachability
     * @throws Exception If the request body is malformed or the command is unknown
     */
    @Override
    public void handleRequest(HttpRequest request, HttpResponse response,
                              AdapterCallback callback) throws Exception {

        if (request.getBody() == null || request.getBody().isEmpty()) {
            response.setStatusCode(400);
            response.setBody("{\"error\":\"Empty request body\"}");
            callback.onSuccess();
            return;
        }

        JsonNode backendRequest = MAPPER.readTree(request.getBody());

        if (backendRequest.get("command") == null || backendRequest.get("command").isNull()) {
            throw new CorruptedConfiguration("Missing 'command' field in request body");
        }

        String commandName = backendRequest.get("command").stringValue();
        HttpCommandDefinition commandDefinition = commandDefinitions.get(commandName);

        if (commandDefinition == null) {
            throw new IllegalOperation("Unknown command: " + commandName);
        }

        int reqHash = request.hashCode();
        Instant translateStart = Instant.now();
        logger.time("Http Adapter: request to be translated (" + reqHash + ")");

        JsonNode finalPayload = translationEngine.translate(commandDefinition, backendRequest);

        Instant translateEnd = Instant.now();
        logger.time("Http Adapter: request translated - time took:"
                + (translateEnd.toEpochMilli() - translateStart.toEpochMilli()) + "ms (" + reqHash + ")");

        
        // Mockup mode — skip the network request and return immediately.
        // Translation has already run, so plugin overhead is fully captured.
        response.setStatusCode(200);
        response.setBody("{\"status\":\"mocked\",\"command\":\"" + commandName + "\"}");
        response.setHeader("MODIFIED-BY", "EdgeControl/Protocol-Translation");
        callback.onSuccess();
        return;
    }
}