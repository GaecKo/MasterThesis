package edge_control.device_translation.adapter;

import edge_control.device_translation.config.DeviceConfig;
import edge_control.logger.EdgeControlLogger;
import org.apache.apisix.plugin.runner.HttpResponse;

import java.net.http.HttpRequest;

public class HttpDeviceAdapter implements DeviceAdapter {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    @Override
    public void init(DeviceConfig config) throws Exception {
        logger.info("Initializing device adapter for device: " + config.getDeviceId() + " with fingreprint: " + config.fingerprint());
    }

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response) throws Exception {

    }

    @Override
    public void shutdown() {
        DeviceAdapter.super.shutdown();
    }
}
