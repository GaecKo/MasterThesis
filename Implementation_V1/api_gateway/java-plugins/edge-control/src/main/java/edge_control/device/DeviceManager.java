package edge_control.device;

import edge_control.device.adapter.DeviceAdapter;
import edge_control.device.config.DeviceConfig;
import edge_control.device.registry.DeviceRegistry;
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
