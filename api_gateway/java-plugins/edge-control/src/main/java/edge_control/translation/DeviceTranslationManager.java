package edge_control.translation;

import edge_control.translation.adapter.DeviceAdapter;
import edge_control.translation.config.DeviceConfig;
import edge_control.translation.registry.DeviceRegistry;
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
        } else if (!config.has("gatewayDeviceId")) {
            throw new CorruptedConfiguration("The JSON file you provided misses the following field: 'gatewayDeviceId'" );
        } else if (!config.has("commands")) {
            throw new CorruptedConfiguration("The JSON file you provided misses the following field: 'commands'");
        }

        String deviceId = config.getString("gatewayDeviceId");

        logger.info("Creating adapter for device: " + deviceId);

        deviceRegistry.upsert(new DeviceConfig(
                config
        ));

    }

    public DeviceAdapter get(String deviceId) {
        return deviceRegistry.get(deviceId);
    }

    public boolean deleteDeviceConfig(String requestBody) throws EdgeControlException, CorruptedConfiguration {
        JSONObject config = new JSONObject(requestBody);

        if (!config.has("gatewayDeviceId")) {
            throw new CorruptedConfiguration("The JSON file you provided misses the following field: 'gatewayDeviceId', can't delete device");
        }
        String gatewayDeviceId = config.getString("gatewayDeviceId");
        logger.info("Deleting device config for device: " + gatewayDeviceId);
        return deviceRegistry.delete(gatewayDeviceId);
    }

}
