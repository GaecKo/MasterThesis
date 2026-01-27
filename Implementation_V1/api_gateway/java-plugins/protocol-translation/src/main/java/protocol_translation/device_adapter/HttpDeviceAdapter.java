package protocol_translation.device_adapter;

import org.apache.apisix.plugin.runner.HttpResponse;

import java.net.http.HttpRequest;

public class HttpDeviceAdapter implements DeviceAdapter {
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
