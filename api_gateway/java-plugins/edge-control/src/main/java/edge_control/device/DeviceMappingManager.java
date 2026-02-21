package edge_control.device;

import edge_control.device.adapter.DeviceAdapter;
import edge_control.device.config.DeviceConfig;
import edge_control.device.registry.DeviceRegistry;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import org.json.JSONObject;

public class DeviceMappingManager {

    private static DeviceMappingManager instance;
    private final DeviceRegistry deviceRegistry;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private DeviceMappingManager(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    public static DeviceMappingManager getInstance() {
        if (instance == null) {
            instance = new DeviceMappingManager(DeviceRegistry.getInstance());
        }
        return instance;
    }

    public void createAdapter(String requestBody) throws CorruptedConfiguration, EdgeControlException {

        JSONObject config = new JSONObject(requestBody);

        if (!config.has("adapter")) {
            throw new CorruptedConfiguration("The JSON file you provided misses the following field: 'adapter'" );
        } else if (!config.has("deviceID")) {
            throw new CorruptedConfiguration("The JSON file you provided misses the following field: 'deviceID'" );
        }

        String adapter = config.getString("adapter");
        String deviceId = config.getString("deviceID");

        logger.info("Creating adapter for device: " + deviceId);

        deviceRegistry.upsert(new DeviceConfig(
                deviceId,
                adapter,
                config
        ));

    }

    public DeviceAdapter get(String deviceId) {
        return deviceRegistry.get(deviceId);
    }



}
