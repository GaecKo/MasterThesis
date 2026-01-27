package protocol_translation.device;

import org.json.JSONObject;
import protocol_translation.device.adapter.DeviceAdapter;
import protocol_translation.device.config.DeviceConfig;
import protocol_translation.device.registry.DeviceRegistry;
import protocol_translation.logger.ProtocolTranslationLogger;

public class DeviceManager {

    private static DeviceManager instance;
    private final DeviceRegistry deviceRegistry;

    private static final ProtocolTranslationLogger logger = ProtocolTranslationLogger.getInstance();

    private DeviceManager(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    public static DeviceManager getInstance() {
        if (instance == null) {
            instance = new DeviceManager(DeviceRegistry.getInstance());
        }
        return instance;
    }

    public void createAdapter(String requestBody) {

        JSONObject config = new JSONObject(requestBody);
        String deviceId = config.getString("deviceId");
        String adapter = config.getString("adapter");

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
