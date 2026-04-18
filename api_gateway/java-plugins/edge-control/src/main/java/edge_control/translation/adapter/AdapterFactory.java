package edge_control.translation.adapter;

import edge_control.translation.config.DeviceConfig;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Creates DeviceAdapter instances based on the adapter type declared in a DeviceConfig.
 * New adapter types can be registered by adding an entry to the adapters map.
 */
public class AdapterFactory {

    private final Map<String, Supplier<DeviceAdapter>> adapters = Map.of(
            "mqtt", MqttDeviceAdapter::new,
            "http", HttpDeviceAdapter::new
    );

    /**
     * Instantiates a new DeviceAdapter for the given device configuration.
     *
     * @param config Device configuration declaring the adapter type via getAdapter()
     * @return A fresh, uninitialised DeviceAdapter instance
     * @throws IllegalArgumentException If the adapter type is not registered
     */
    public DeviceAdapter create(DeviceConfig config) {
        Supplier<DeviceAdapter> supplier = adapters.get(config.getAdapter());
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown adapter: " + config.getAdapter());
        }
        return supplier.get();
    }
}