package edge_control.translation.adapter;



import edge_control.translation.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;

import java.util.concurrent.CompletableFuture;

public interface DeviceAdapter {

    /** Called once when the device is created  */
    void init(DeviceConfig config) throws Exception;

    /** Handle a request coming from APISIX/backend */
    void handleRequest(HttpRequest request, HttpResponse response,
                       CompletableFuture<Void> completionFuture) throws Exception;

    /** Optional: called on config reload or shutdown */
    default void shutdown() {}
}

