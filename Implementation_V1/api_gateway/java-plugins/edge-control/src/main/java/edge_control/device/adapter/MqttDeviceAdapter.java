package edge_control.device.adapter;

import edge_control.device.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpResponse;

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
