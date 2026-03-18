package edge_control.translation.adapter;

import edge_control.exceptions.EdgeControlException;
import edge_control.translation.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;

public interface DeviceAdapter {

    /** Called once when the device is created. */
    void init(DeviceConfig config) throws EdgeControlException;

    /**
     * Handle a request coming from APISIX/backend.
     * Must always call either callback.onSuccess() or callback.onDeviceUnreachable()
     * exactly once, even on error — never leave the chain hanging.
     */
    void handleRequest(HttpRequest request, HttpResponse response,
                       AdapterCallback callback) throws Exception;

    /** Optional: called on config reload or shutdown. */
    default void shutdown() {}
}