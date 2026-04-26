package edge_control.auth.device;

import edge_control.database.DeviceAuthorizationsRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.HashMap;

/**
 * Handles device lifecycle operations: creation, update, deletion, and authorization management.
 * Delegates persistence to DeviceConfigRepository and DeviceAuthorizationsRepository.
 */
public class DeviceManager {

    private static DeviceManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final DeviceConfigRepository           deviceConfig         = new DeviceConfigRepository();
    private final DeviceAuthorizationsRepository   deviceAuthorizations = new DeviceAuthorizationsRepository();

    private DeviceManager() {
        logger.info("DeviceManager initialized");
    }

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized DeviceManager getInstance() {
        if (instance == null) {
            instance = new DeviceManager();
        }
        return instance;
    }

    // | ================= Device config ================= |

    /**
     * Creates config entries for all devices in the request body.
     * Returns a document mapping each deviceName to its assigned gatewayDeviceId and plain-text API key.
     *
     * @param body JSON string containing 'listOfDevices'
     * @return Document of { deviceName → { gatewayDeviceId, apiKey } }
     */
    public Document createDevice(String body) {
        HashMap<String, DeviceConfigRepository.DeviceCreationResult> result =
                deviceConfig.createDeviceConfig(Document.parse(body));

        Document responseDoc = new Document();
        result.forEach((deviceName, creationResult) -> {
            logger.info("Created device " + deviceName
                    + " gatewayDeviceId: " + creationResult.gatewayDeviceId()
                    + " with API key: " + creationResult.apiKey());
            Document deviceInfo = new Document();
            deviceInfo.put("apiKey",           creationResult.apiKey());
            deviceInfo.put("gatewayDeviceId",  creationResult.gatewayDeviceId());
            responseDoc.put(deviceName, deviceInfo);
        });

        return responseDoc;
    }

    /**
     * Updates an existing device config entry.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId' and updated fields
     * @return Document with 'status' ("success" or "failure")
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
     * Deletes a device config and its authorization entry.
     * Both deletions are attempted independently; partial failures are reported.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId'
     * @return Document with 'status' and 'message' describing the outcome
     */
    public Document deleteDevice(String requestBody) {
        // Parse once — both repositories need the same document
        Document parsed = Document.parse(requestBody);

        boolean authzSuccess  = deviceAuthorizations.deleteDeviceAuthorization(parsed);
        boolean configSuccess = deviceConfig.deleteDeviceConfig(parsed);

        Document responseDoc = new Document();
        if (authzSuccess && configSuccess) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Deleted the device configuration and all related authorizations.");
        } else if (!authzSuccess && !configSuccess) {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to delete device configuration and related authorizations. Device may not exist or invalid request format.");
        } else if (!authzSuccess) {
            responseDoc.put("status", "partial_failure");
            responseDoc.put("message", "Failed to delete related authorizations. Device configuration deleted successfully.");
        } else {
            responseDoc.put("status", "partial_failure");
            responseDoc.put("message", "Failed to delete device configuration. Related authorizations deleted successfully.");
        }
        return responseDoc;
    }

    // | ================= Device authorization ================= |

    /**
     * Adds an authorization entry for a device.
     * Returns failure if the device does not exist in the config collection.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId' and 'listOfAuthorizations'
     * @return Document with 'status' and 'message'
     */
    public Document addDeviceAuthorizationConfig(String requestBody) {
        Document parsed = Document.parse(requestBody);

        // Verify the device exists before creating an authorization entry for it
        if (!deviceConfig.deviceExists(parsed)) {
            Document responseDoc = new Document();
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to add device authorization entry. Device does not exist in configuration collection.");
            logger.error("Failed to add device authorization entry. Device does not exist in configuration collection.");
            return responseDoc;
        }

        boolean success = deviceAuthorizations.addDeviceAuthorization(parsed);
        Document responseDoc = new Document();

        if (success) {
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
     * Updates authorization details for a device.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId' and lists of authorizations to add/remove
     * @return Document with 'status' and 'message'
     */
    public Document updateDeviceAuthorizationConfig(String requestBody) {
        boolean success = deviceAuthorizations.updateDeviceAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Device authorization updated successfully.");
            logger.info("Updated device authorization");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to update device authorization. Device may not exist or invalid request format.");
            logger.error("Failed to update device authorization");
        }
        return responseDoc;
    }

    /**
     * Deletes the authorization entry for a device.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId'
     * @return Document with 'status' and 'message'
     */
    public Document deleteDeviceAuthorizationConfig(String requestBody) {
        boolean success = deviceAuthorizations.deleteDeviceAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Device authorization deleted successfully.");
            logger.info("Deleted device authorization");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to delete device authorization. Device may not exist or invalid request format.");
            logger.error("Failed to delete device authorization. Device may not exist.");
        }
        return responseDoc;
    }

    /**
     * Removes a backend from the authorization list of all devices.
     * Called when a backend is deleted to prevent dangling references.
     *
     * @param requestBody JSON string containing 'gatewayBackendId'
     * @return Document with 'status'
     */
    public Document removeAllBackendsFromAuthorization(String requestBody) {
        boolean success = deviceAuthorizations.removeBackendFromDeviceAuthorizations(
                Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            logger.info("Removed backend from all device authorizations");
        } else {
            responseDoc.put("status", "failure");
            logger.error("Failed to remove backend from device authorizations");
        }
        return responseDoc;
    }
}