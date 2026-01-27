package protocol_translation.device.adapter;

import java.util.Map;
import java.util.function.Supplier;

public class AdapterFactory {

    private final Map<String, Supplier<DeviceAdapter>> adapters = Map.of(
            "mqtt", MqttDeviceAdapter::new,
            "http", HttpDeviceAdapter::new
    );

    public DeviceAdapter create(String type) {
        Supplier<DeviceAdapter> supplier = adapters.get(type);
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown adapter: " + type);
        }
        return supplier.get();
    }
}

