package protocol_translation.device.adapter;

import org.apache.apisix.plugin.runner.HttpResponse;
import protocol_translation.device.config.DeviceConfig;

import java.net.http.HttpRequest;

public class MqttDeviceAdapter implements DeviceAdapter {
    @Override
    public void init(DeviceConfig config) throws Exception {

    }

    @Override
    public HttpResponse handle(HttpRequest request) throws Exception {
        return null;
    }

    @Override
    public void shutdown() {
        DeviceAdapter.super.shutdown();
    }
}
