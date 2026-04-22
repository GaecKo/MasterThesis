package edge_control.translation.adapter;

import edge_control.exceptions.EdgeControlException;
import edge_control.translation.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;

/**
 * Contract for device protocol adapters (HTTP, MQTT, etc.).
 * Implementations must never block the Netty event loop thread inside handleRequest.
 */
public interface DeviceAdapter {

    /**
     * Initialises the adapter from the device configuration.
     * Called once on onboarding or config update.
     *
     * @param config Device configuration containing connection and command settings
     * @throws EdgeControlException If the config is invalid or the connection fails
     */
    void init(DeviceConfig config) throws EdgeControlException;

    /**
     * Releases resources. No-op by default for stateless adapters.
     */
    default void shutdown() {}

    /**
     * Translates and forwards a command to the device asynchronously.
     * Must call exactly one of callback.onSuccess() or callback.onDeviceUnreachable() — always.
     *
     * @param request  Inbound HTTP request containing the command and its parameters
     * @param response HTTP response to populate before calling onSuccess
     * @param callback Callback to signal the outcome once the operation completes
     * @throws Exception If the request is malformed or fails before async work begins
     */
    void handleRequest(HttpRequest request, HttpResponse response,
                       AdapterCallback callback) throws Exception;
}