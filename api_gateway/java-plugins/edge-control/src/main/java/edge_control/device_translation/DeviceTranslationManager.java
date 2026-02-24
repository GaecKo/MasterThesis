package edge_control.device_translation;

import edge_control.device_translation.adapter.DeviceAdapter;
import edge_control.device_translation.config.DeviceConfig;
import edge_control.device_translation.registry.DeviceRegistry;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import org.json.JSONObject;

public class DeviceTranslationManager {

    private static DeviceTranslationManager instance;
    private final DeviceRegistry deviceRegistry;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private DeviceTranslationManager(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    public static DeviceTranslationManager getInstance() {
        if (instance == null) {
            instance = new DeviceTranslationManager(DeviceRegistry.getInstance());
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
