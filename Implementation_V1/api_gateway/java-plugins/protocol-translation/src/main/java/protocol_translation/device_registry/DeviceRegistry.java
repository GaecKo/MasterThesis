package protocol_translation.device_registry;

import protocol_translation.device_adapter.AdapterFactory;
import protocol_translation.device_adapter.DeviceAdapter;
import protocol_translation.device_adapter.DeviceConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceRegistry {

    private final Map<String, DeviceAdapter> devices = new ConcurrentHashMap<>();
    private final AdapterFactory adapterFactory = new AdapterFactory();
    private static DeviceRegistry instance;

    public void load(List<DeviceConfig> configs) {
        for (DeviceConfig config : configs) {
            devices.compute(config.getDeviceId(), (id, existing) -> {
                if (existing != null) {
                    existing.shutdown();
                }
                return createAdapter(config);
            });
        }
    }

    public DeviceAdapter get(String deviceId) {
        return devices.get(deviceId);
    }

    private DeviceAdapter createAdapter(DeviceConfig config) {
        DeviceAdapter adapter = adapterFactory.create(config.getAdapter());
        try {
            adapter.init(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to init device " + config.getDeviceId(), e);
        }
        return adapter;
    }

    public static synchronized DeviceRegistry getInstance() {
        if (instance == null) {
            instance = new DeviceRegistry();
        }
        return instance;
    }
}

