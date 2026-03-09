package edge_control.backend;

import edge_control.auth.AuthRegistry;
import edge_control.database.BackendAuthorizationsRepository;
import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.List;

public class BackendManager {

    private static BackendManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();

    private final BackendAuthorizationsRepository backendAuthorizations = new BackendAuthorizationsRepository();

    private final DeviceConfigRepository deviceConfig = new DeviceConfigRepository();

    private final AuthRegistry authRegistry = AuthRegistry.getInstance();

    private BackendManager() {
        // Initialize database configuration on first instantiation
        logger.info("Database initialized");
    }

    public static BackendManager getInstance() {
        if (instance == null) {
            instance = new BackendManager();
        }
        return instance;
    }

    /**
     * Creates a new backend configuration and returns both the backend ID and API key.
     *
     * @param body the backend configuration JSON string
     * @return a Document containing "gatewayBackendId" and "apiKey"
     */
    public Document createBackend(String body) {
        BackendConfigRepository.BackendCreationResult result =
            backendConfig.createBackendConfig(Document.parse(body));

        logger.info("Created backend with ID: " + result.gatewayBackendId);

        // Return both ID and API key
        Document responseDoc = new Document();
        responseDoc.put("message", "Backend created successfully.");
        responseDoc.put("apiKey", result.apiKey);
        responseDoc.put("gatewayBackendId", result.gatewayBackendId);
        return responseDoc;
    }

    /**
     * Updates an existing backend configuration (PATCH operation).
     * Modifies communication details for an existing backend.
     *
     * @param requestBody the body of the request containing the backend ID and updated configuration details
     * @return a Document with status and message
     */
    public Document updateBackend(String requestBody) {
        boolean success = backendConfig.updateBackendConfig(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Backend configuration updated successfully.");
            logger.info("Updated backend configuration");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to update backend configuration. Backend may not exist or invalid request format.");
            logger.error("Failed to update backend configuration");
        }
        return responseDoc;
    }

    /**
     * Deletes a backend configuration and all related authorizations.
     * @param requestBody the ID of the backend to delete
     * @return a Document with status and message
     */
    public Document deleteBackend(String requestBody) {
        boolean authzSuccess = backendAuthorizations.deleteBackendAuthorization(Document.parse(requestBody));
        boolean configSuccess = backendConfig.deleteBackendConfig(Document.parse(requestBody));
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
     * Add a new backend authorization entry (POST operation).
     * Creates a new entry for a backend with initial authorization details.
     *
     * @param requestBody the body of the request containing the backend ID and authorization details
     * @return a Document with status and message
     */
    public Document addBackendAuthorizationConfig(String requestBody) {
        // Check if backend was added in the config collection before adding authorization entry
        if (!backendConfig.backendExists(Document.parse(requestBody))) {
            Document responseDoc = new Document();
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to add backend authorization entry. Backend does not exist in configuration collection.");
            logger.error("Failed to add backend authorization entry. Backend does not exist in configuration collection.");
            return responseDoc;
        }

        boolean success = backendAuthorizations.addBackendAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Backend authorization entry added successfully.");
            logger.info("Added new backend authorization entry");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to add backend authorization entry. Backend may already exist or invalid request format.");
            logger.error("Failed to add new backend authorization entry. Backend may already exist.");
        }
        return responseDoc;
    }

    /**
     * Update an existing backend authorization entry (PATCH operation).
     * Modifies authorization details for an existing backend.
     *
     * @param requestBody the body of the request containing the backend ID and authorization details
     * @return a Document with status and message
     */
    public Document updateBackendAuthorizationConfig(String requestBody) {
        boolean success = backendAuthorizations.updateBackendAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Backend authorization updated successfully.");
            logger.info("Updated backend authorization");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to update backend authorization. Backend may not exist or invalid request format.");
            logger.error("Failed to update backend authorization");
        }
        return responseDoc;
    }

    /**
     * Deletes a backend entry and all related authorization entries from the backendAuthorization collection.
     * Removes the backend entry and all related authorization entries backendAuthorization collection.
     *
     * @param requestBody the ID of the backend to delete
     * @return a Document with status and message
     */
    public Document deleteBackendAuthorizationConfig(String requestBody) {
        boolean success = backendAuthorizations.deleteBackendAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Backend and related authorizations deleted successfully.");
            logger.info("Deleted backend and related authorizations");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to delete backend. Backend may not exist or invalid request format.");
            logger.error("Failed to delete backend. Backend may not exist.");
        }
        return responseDoc;
    }

    /**
     * Remove gatewayDeviceId and info from backend authorization entry.
     *
     * @param requestBody the body of the request containing the device ID to remove
     * @return true if removal was successful, false otherwise
     */
    public Document removeAllDevicesFromAuthorization(String requestBody) {
        boolean success = backendAuthorizations.removeDeviceFromBackendAuthorizations(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status", "success");
            responseDoc.put("message", "Removed device from backend authorizations successfully.");
            logger.info("Removed device from backend authorizations");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to remove device from backend authorizations. Device may not exist or invalid request format.");
            logger.error("Failed to remove device from backend authorizations");
        }
        return responseDoc;
    }

    /**
     * Get a list of authorized devices with their commands and params for a specified backend.
     * Returns enriched device info including deviceName, type, and full command details.
     *
     * @param authenticationcheckerResult the result of the authentication check containing the backend ID
     * @return a Document containing the enriched authorizations for the specified backend
     **/
    public Document getBackendAuthorizedCommands(String authenticationcheckerResult){
        Document authzList = authRegistry.getBackendAuth(authenticationcheckerResult);

        if (authzList != null) {
            logger.info("AuthRegistry cache hit for backend auth");
        } else {
            logger.info("AuthRegistry cache miss for backend auth");
            authzList = backendAuthorizations.findAuthorizationsById(authenticationcheckerResult);
            if (authzList != null) {
                authRegistry.putBackendAuth(authenticationcheckerResult, authzList);
            }
        }

        Document responseDoc = new Document();

        if (authzList != null) {
            // Build enriched response: for each authorized device, fetch its info and filter commands
            Document authorizedDevices = new Document();

            for (String gatewayDeviceId : authzList.keySet()) {
                List<String> authorizedCommandNames = authzList.getList(gatewayDeviceId, String.class);
                if (authorizedCommandNames == null) continue;

                // Fetch device info from deviceConfig
                Document deviceInfo = deviceConfig.findDeviceById(gatewayDeviceId);

                Document deviceEntry = new Document();
                if (deviceInfo != null) {
                    deviceEntry.put("deviceName", deviceInfo.getString("deviceName"));

                    // Get commands from device config and filter to only authorized ones
                    Document allCommands = (Document) deviceInfo.get("commands");
                    if (allCommands != null) {
                        Document filteredCommands = new Document();
                        for (String commandName : authorizedCommandNames) {
                            Object commandDetail = allCommands.get(commandName);
                            if (commandDetail != null) {
                                filteredCommands.put(commandName, commandDetail);
                            }
                        }
                        deviceEntry.put("commands", filteredCommands);
                    }
                } else {
                    // Device config not found, return minimal info
                    deviceEntry.put("deviceName", "unknown");
                    deviceEntry.put("type", "unknown");
                    deviceEntry.put("commands", new Document());
                }

                authorizedDevices.put(gatewayDeviceId, deviceEntry);
            }

            responseDoc.put("status", "success");
            responseDoc.put("message", "Retrieved backend authorizations successfully.");
            responseDoc.put("authorizedDevices", authorizedDevices);
            logger.info("Retrieved backend authorizations successfully.");
        } else {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "Failed to retrieve backend authorizations. Backend may not exist or invalid request format.");
            logger.error("Failed to retrieve backend authorizations. Backend may not exist.");
        }
        return responseDoc;
    }

}

