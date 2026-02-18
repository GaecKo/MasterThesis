package edge_control.device.adapter;

import edge_control.device.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpResponse;

import java.net.http.HttpRequest;

public class TestAdapter implements DeviceAdapter {

    @Override
    public void init(DeviceConfig config) throws Exception {

    }

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response) throws Exception {

    }
}
