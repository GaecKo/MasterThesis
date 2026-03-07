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
            deviceInfo.put("apiKey", creationResult.apiKey);
            deviceInfo.put("gatewayDeviceId", creationResult.gatewayDeviceId);
            responseDoc.put(deviceName, deviceInfo);
        });

        return responseDoc;
    }

    /**
     * Updates an existing device configuration (PATCH operation).
     * Modifies communication details for an existing device.
     *
     * @param requestBody the body of the request containing the device ID and updated configuration details
     * @return a Document with status and message
     */
    public Document updateDevice(String requestBody) {
        boolean success = deviceConfig.updateDeviceConfig(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            logger.info("Updated device configuration");
        } else {
            responseDoc.put("status", "failure");
            logger.error("Failed to update device configuration");
        }
        return responseDoc;
    }

    /**
     * Deletes a backend configuration and all related authorizations.
     * @param requestBody the ID of the backend to delete
     * @return a Document with status and message
     */
    public Document deleteDevice(String requestBody) {
        boolean authzSuccess = deviceAuthorizations.deleteDeviceAuthorization(Document.parse(requestBody));
        boolean configSuccess = deviceConfig.deleteDeviceConfig(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (authzSuccess && configSuccess){
            responseDoc.put("status", "success");
            responseDoc.put("message", "Deleted the backend configuration and all related authorizations.");
        } else if (!authzSuccess && !configSuccess) {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to delete backend configuration and related authorizations. Backend may not exist or invalid request format.");
        } else if (!authzSuccess) {
            responseDoc.put("status", "partial_failure");
            responseDoc.put("message", "Failed to delete related authorizations. Backend configuration deleted successfully.");
        } else {
            responseDoc.put("status", "partial_failure");
            responseDoc.put("message", "Failed to delete backend configuration. Related authorizations deleted successfully.");
        }

        return responseDoc;
    }

    /**
     * Add authorizations for a device.
     *
     * @param requestBody the body fo the request containing the device ID and authorization details
     * @return true if addition was successful, false otherwise
     */
    public Document addDeviceAuthorizationConfig(String requestBody) {
        // Check if device exists before adding authorization
        if (!deviceConfig.deviceExists(Document.parse(requestBody))) {
            Document responseDoc = new Document();
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to add device authorization entry. Device does not exist in configuration collection.");
            logger.error("Failed to add device authorization entry. Device does not exist in configuration collection.");
            return responseDoc;
        }

        boolean succes = deviceAuthorizations.addDeviceAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (succes) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Device authorization added successfully.");
            logger.info("Added device authorization");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to add device authorization. Authorization entry may already exist or invalid request format.");
            logger.error("Failed to add device authorization");
        }
        return responseDoc;
    }

    /**
     * Update device authorization details for a specific device.
     *
     * @param requestBody the body of the request containing the device ID and updated authorization details
     * @return true if update was successful, false otherwise
     */
    public Document updateDeviceAuthorizationConfig(String requestBody) {
        boolean succes = deviceAuthorizations.updateDeviceAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (succes) {
            responseDoc.put("status", "success");
            logger.info("Updated device authorization");
        } else {
            responseDoc.put("status", "failure");
            logger.error("Failed to update device authorization");
        }
        return responseDoc;
    }

    /**
     * Delete a device entry in deviceAUthorizations collection.
     * This is used when a device is deleted to ensure that no authorization entry remains for a non-existent device.
     *
     * @param requestBody the body of the request containing the device ID to delete
     * @return true if deletion was successful, false otherwise
     */
    public Document deleteDeviceAuthorizationConfig(String requestBody) {
        boolean success = deviceAuthorizations.deleteDeviceAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Device and related authorizations deleted successfully.");
            logger.info("Deleted device and related authorizations");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to delete device. Device may not exist or invalid request format.");
            logger.error("Failed to delete device. device may not exist.");
        }
        return responseDoc;
    }

    /**
     * Remove gatewayBackendId from device authorization entry.
     *
     * @param requestBody the body of the request containing the backend ID to remove
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
