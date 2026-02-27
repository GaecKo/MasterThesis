package edge_control.device;

import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceAuthorizationsRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.HashMap;

public class DeviceManager {

    private static DeviceManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final DeviceConfigRepository deviceConfig = new DeviceConfigRepository();

    private final DeviceAuthorizationsRepository deviceAuthorizations = new DeviceAuthorizationsRepository();

    private DeviceManager() {
        logger.info("DeviceManager initialized");
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

    /**
     * Add authorizations for a device.
     *
     * @param requestBody the body fo the request containing the device ID and authorization details
     * @return true if addition was successful, false otherwise
     */
    public Document addDeviceAuthorizationConfig(String requestBody) {
        boolean succes = deviceAuthorizations.addDeviceAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (succes) {
            responseDoc.put("status", "success");
            logger.info("Added device authorization");
        } else {
            responseDoc.put("status", "failure");
            logger.error("Failed to add device authorization");
        }
        return responseDoc;
    }

    /**
     * Remove gatewayBackendId from device authorization entry.
     *
     * @param requestBody the body of the request containing the device ID and backend ID to remove
     * @return true if removal was successful, false otherwise
     */
    public Document removeAllBackendsFromAuthorization(String requestBody) {
        boolean succes = deviceAuthorizations.removeBackendFromDeviceAuthorizations(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (succes) {
            responseDoc.put("status", "success");
            logger.info("Removed device authorization");
        } else {
            responseDoc.put("status", "failure");
            logger.error("Failed to remove device authorization");
        }

        return responseDoc;
    }

}
