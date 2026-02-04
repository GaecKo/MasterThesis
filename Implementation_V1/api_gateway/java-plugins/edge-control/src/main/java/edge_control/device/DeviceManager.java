package edge_control.device;

import edge_control.device.adapter.DeviceAdapter;
import edge_control.device.config.DeviceConfig;
import edge_control.device.registry.DeviceRegistry;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.logger.EdgeControlLogger;
import org.json.JSONObject;

public class DeviceManager {

    private static DeviceManager instance;
    private final DeviceRegistry deviceRegistry;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private DeviceManager(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    public static DeviceManager getInstance() {
        if (instance == null) {
            instance = new DeviceManager(DeviceRegistry.getInstance());
        }
        return instance;
    }

    public String createAdapter(String requestBody) throws CorruptedConfiguration {

        JSONObject config = new JSONObject(requestBody);

        if (!config.has("adapter")) {
            throw new CorruptedConfiguration("The JSON file you provided misses the following field: 'adapter'" );
        }

        String adapter = config.getString("adapter");
        String deviceId = null;

        if (config.has("deviceID")) {
            deviceId = config.getString("deviceID");
        }

        // logger.info("Creating adapter for device: " + deviceId);

        return deviceRegistry.upsert(new DeviceConfig(
                deviceId,
                adapter,
                config
        ));

    }

    public DeviceAdapter get(String deviceId) {
        return deviceRegistry.get(deviceId);
    }



}
