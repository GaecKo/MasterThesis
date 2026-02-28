package edge_control.translation.adapter;

import edge_control.translation.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;



public class MqttDeviceAdapter implements DeviceAdapter {
    @Override
    public void init(DeviceConfig config) throws Exception {

    }

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response) throws Exception {

    }

    @Override
    public void shutdown() {
        DeviceAdapter.super.shutdown();
    }
}
