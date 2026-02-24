package edge_control.device_translation.adapter;

import edge_control.device_translation.config.DeviceConfig;

import java.util.Map;
import java.util.function.Supplier;

public class AdapterFactory {

    private final Map<String, Supplier<DeviceAdapter>> adapters = Map.of(
            "mqtt", MqttDeviceAdapter::new,
            "http", HttpDeviceAdapter::new
    );

    public DeviceAdapter create(DeviceConfig config) {
        Supplier<DeviceAdapter> supplier = adapters.get(config.getAdapter());
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown adapter: " + config.getAdapter());
        }
        return supplier.get();
    }
}

