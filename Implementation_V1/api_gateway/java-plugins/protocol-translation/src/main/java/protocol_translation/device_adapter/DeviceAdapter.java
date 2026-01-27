package protocol_translation.device_adapter;

import java.net.http.HttpRequest;

import org.apache.apisix.plugin.runner.HttpResponse;

public interface DeviceAdapter {

    /** Called once when the device is created  */
    void init(DeviceConfig config) throws Exception;

    /** Handle a request coming from APISIX/backend */
    HttpResponse handle(HttpRequest request) throws Exception;

    /** Optional: called on config reload or shutdown */
    default void shutdown() {}
}

