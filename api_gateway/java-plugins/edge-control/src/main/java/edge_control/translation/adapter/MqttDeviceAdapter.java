package edge_control.translation.adapter;

import edge_control.translation.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;

import java.util.concurrent.CompletableFuture;


public class MqttDeviceAdapter implements DeviceAdapter {
    @Override
    public void init(DeviceConfig config) throws Exception {

    }

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response, CompletableFuture<Void> completionFuture) throws Exception {

    }

    @Override
    public void shutdown() {
        DeviceAdapter.super.shutdown();
    }
}
