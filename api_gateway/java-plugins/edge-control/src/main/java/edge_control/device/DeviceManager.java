package edge_control.device;

import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.HashMap;

public class DeviceManager {

    private static DeviceManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final DeviceConfigRepository deviceConfig = new DeviceConfigRepository();

    private DeviceManager() {
        // Initialize database configuration on first instantiation
        logger.info("Database initialized");
    }

    public static DeviceManager getInstance() {
        if (instance == null) {
            instance = new DeviceManager();
        }
        return instance;
    }

    /**
     * Creates a new device configuration and returns both the device ID and API key.
     *
     * @param body the device configuration JSON string
     * @return a Document containing "gatewaydeviceId" and "apiKey"
     */
    public Document createDevice(String body) {
        HashMap<String, DeviceConfigRepository.DeviceCreationResult> result =
                deviceConfig.createDeviceConfig(Document.parse(body));

        result.forEach((deviceName, creationResult) -> {
            logger.info("Created device " + deviceName + "gatewayDeviceId: " + creationResult.gatewayDeviceId + " with API key: " + creationResult.apiKey);
        });

        // Return both ID and API key for each device
        Document responseDoc = new Document();
        result.forEach((deviceName, creationResult) -> {
            Document deviceInfo = new Document();
            deviceInfo.put("gatewayDeviceId", creationResult.gatewayDeviceId);
            deviceInfo.put("apiKey", creationResult.apiKey);
            responseDoc.put(deviceName, deviceInfo);
        });

        return responseDoc;
    }

}
