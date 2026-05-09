package edge_control.auth.backend;

import edge_control.auth.AuthRegistry;
import edge_control.auth.tokens.GatewayTokensRegistry;
import edge_control.database.BackendAuthorizationsRepository;
import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.database.GatewayTokensRepository;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;
import org.json.JSONObject;

import java.util.List;

/**
 * Handles backend lifecycle operations: creation, update, deletion, and authorization management.
 * Delegates persistence to BackendConfigRepository and BackendAuthorizationsRepository.
 */
public class BackendManager {

    private static BackendManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendConfigRepository          backendConfig         = new BackendConfigRepository();
    private final BackendAuthorizationsRepository  backendAuthorizations = new BackendAuthorizationsRepository();
    private final DeviceConfigRepository           deviceConfig          = new DeviceConfigRepository();
    private final AuthRegistry                     authRegistry          = AuthRegistry.getInstance();
    private final GatewayTokensRegistry            gatewayTokensRegistry  = GatewayTokensRegistry.getInstance();

    private BackendManager() throws EdgeControlException {
        logger.info("BackendManager initialized");
    }

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized BackendManager getInstance() throws EdgeControlException {
        if (instance == null) {
            instance = new BackendManager();
        }
        return instance;
    }

    // | ================= Backend config ================= |

    /**
     * Creates a new backend config entry and returns the assigned ID and plain-text API key.
     *
     * @param body JSON string containing backend configuration fields
     * @return Document with 'gatewayBackendId', 'apiKey', and 'message'
     */
    public Document createBackend(String body) {
        Document parsedBody = Document.parse(body);
        Document securityBody = parsedBody.get("security", Document.class);

        parsedBody.remove("security");
        BackendConfigRepository.BackendCreationResult result =
                backendConfig.createBackendConfig(parsedBody);

        if (securityBody != null) {
            JSONObject securityObject = new JSONObject(securityBody);
            gatewayTokensRegistry.upsertToken(result.gatewayBackendId(), securityObject);
        }

        logger.info("Created backend with ID: " + result.gatewayBackendId());

        Document responseDoc = new Document();
        responseDoc.put("message",          "Backend created successfully.");
        responseDoc.put("apiKey",           result.apiKey());
        responseDoc.put("gatewayBackendId", result.gatewayBackendId());
        return responseDoc;
    }

    /**
     * Updates an existing backend config entry.
     *
     * @param requestBody JSON string containing 'gatewayBackendId' and updated fields
     * @return Document with 'status' and 'message'
     */
    public Document updateBackend(String requestBody) {
        boolean success = backendConfig.updateBackendConfig(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status",  "success");
            responseDoc.put("message", "Backend configuration updated successfully.");
            logger.info("Updated backend configuration");
        } else {
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to update backend configuration. Backend may not exist or invalid request format.");
            logger.error("Failed to update backend configuration");
        }
        return responseDoc;
    }

    /**
     * Deletes a backend config and its authorization entry.
     * Both deletions are attempted independently; partial failures are reported.
     *
     * @param requestBody JSON string containing 'gatewayBackendId'
     * @return Document with 'status' and 'message' describing the outcome
     */
    public Document deleteBackend(String requestBody) {
        // Parse once — both repositories need the same document
        Document parsed = Document.parse(requestBody);

        boolean authzSuccess  = backendAuthorizations.deleteBackendAuthorization(parsed);
        boolean configSuccess = backendConfig.deleteBackendConfig(parsed);

        Document responseDoc = new Document();
        if (authzSuccess && configSuccess) {
            responseDoc.put("status",  "success");
            responseDoc.put("message", "Deleted the backend configuration and all related authorizations.");
        } else if (!authzSuccess && !configSuccess) {
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to delete backend configuration and related authorizations. Backend may not exist or invalid request format.");
        } else if (!authzSuccess) {
            responseDoc.put("status",  "partial_failure");
            responseDoc.put("message", "Failed to delete related authorizations. Backend configuration deleted successfully.");
        } else {
            responseDoc.put("status",  "partial_failure");
            responseDoc.put("message", "Failed to delete backend configuration. Related authorizations deleted successfully.");
        }
        return responseDoc;
    }

    // | ================= Backend authorization ================= |

    /**
     * Adds an authorization entry for a backend.
     * Returns failure if the backend does not exist in the config collection.
     *
     * @param requestBody JSON string containing 'gatewayBackendId' and 'listOfAuthorizations'
     * @return Document with 'status' and 'message'
     */
    public Document addBackendAuthorizationConfig(String requestBody) {
        Document parsed = Document.parse(requestBody);

        // Verify the backend exists before creating an authorization entry for it
        if (!backendConfig.backendExists(parsed)) {
            Document responseDoc = new Document();
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to add backend authorization entry. Backend does not exist in configuration collection.");
            logger.error("Failed to add backend authorization entry. Backend does not exist in configuration collection.");
            return responseDoc;
        }

        boolean success = backendAuthorizations.addBackendAuthorization(parsed);
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status",  "success");
            responseDoc.put("message", "Backend authorization entry added successfully.");
            logger.info("Added new backend authorization entry");
        } else {
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to add backend authorization entry. Backend may already exist or invalid request format.");
            logger.error("Failed to add new backend authorization entry. Backend may already exist.");
        }
        return responseDoc;
    }

    /**
     * Updates authorization details for a backend.
     *
     * @param requestBody JSON string containing 'gatewayBackendId' and lists of authorizations to add/remove
     * @return Document with 'status' and 'message'
     */
    public Document updateBackendAuthorizationConfig(String requestBody) {
        boolean success = backendAuthorizations.updateBackendAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status",  "success");
            responseDoc.put("message", "Backend authorization updated successfully.");
            logger.info("Updated backend authorization");
        } else {
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to update backend authorization. Backend may not exist or invalid request format.");
            logger.error("Failed to update backend authorization");
        }
        return responseDoc;
    }

    /**
     * Deletes the authorization entry for a backend.
     *
     * @param requestBody JSON string containing 'gatewayBackendId'
     * @return Document with 'status' and 'message'
     */
    public Document deleteBackendAuthorizationConfig(String requestBody) {
        boolean success = backendAuthorizations.deleteBackendAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status",  "success");
            responseDoc.put("message", "Backend authorization deleted successfully.");
            logger.info("Deleted backend authorization");
        } else {
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to delete backend authorization. Backend may not exist or invalid request format.");
            logger.error("Failed to delete backend authorization. Backend may not exist.");
        }
        return responseDoc;
    }

    /**
     * Removes a device from the authorization list of all backends.
     * Called when a device is deleted to prevent dangling references.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId'
     * @return Document with 'status' and 'message'
     */
    public Document removeAllDevicesFromAuthorization(String requestBody) {
        boolean success = backendAuthorizations.removeDeviceFromBackendAuthorizations(
                Document.parse(requestBody));
        Document responseDoc = new Document();

        if (success) {
            responseDoc.put("status",  "success");
            responseDoc.put("message", "Removed device from backend authorizations successfully.");
            logger.info("Removed device from backend authorizations");
        } else {
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to remove device from backend authorizations. Device may not exist or invalid request format.");
            logger.error("Failed to remove device from backend authorizations");
        }
        return responseDoc;
    }

    // | ================= Queries ================= |

    /**
     * Returns all devices and their authorized commands for a given backend.
     * Each device entry includes its name and the subset of commands the backend is allowed to send.
     * Consults the AuthRegistry cache first; falls back to DB on a miss.
     *
     * @param gatewayBackendId The backend ID to retrieve authorizations for
     * @return Document with 'status' and 'authorizedDevices' (deviceId → { deviceName, commands })
     */
    public Document getBackendAuthorizedCommands(String gatewayBackendId) {
        Document authzList = authRegistry.getBackendAuth(gatewayBackendId);

        if (authzList != null) {
            logger.info("AuthRegistry cache hit for backend auth");
        } else {
            logger.info("AuthRegistry cache miss for backend auth");
            authzList = backendAuthorizations.findAuthorizationsById(gatewayBackendId);
            if (authzList != null) {
                authRegistry.putBackendAuth(gatewayBackendId, authzList);
            }
        }

        Document responseDoc = new Document();

        if (authzList == null) {
            responseDoc.put("status",  "failure");
            responseDoc.put("message", "Failed to retrieve backend authorizations. Backend may not exist or invalid request format.");
            logger.error("Failed to retrieve backend authorizations. Backend may not exist.");
            return responseDoc;
        }

        // Build enriched response: for each authorized device, fetch its info and filter to allowed commands
        Document authorizedDevices = new Document();
        for (String gatewayDeviceId : authzList.keySet()) {
            List<String> authorizedCommandNames = authzList.getList(gatewayDeviceId, String.class);
            if (authorizedCommandNames == null) continue;

            Document deviceEntry = new Document();
            Document deviceInfo  = deviceConfig.findDeviceById(gatewayDeviceId);

            if (deviceInfo != null) {
                deviceEntry.put("deviceName", deviceInfo.getString("deviceName"));

                // Fetch all commands from device config and keep only the ones this backend can send
                Document allCommands = (Document) deviceInfo.get("commands");
                if (allCommands != null) {
                    Document filteredCommands = new Document();
                    for (String commandName : authorizedCommandNames) {
                        Object commandDetail = allCommands.get(commandName);
                        if (commandDetail != null) filteredCommands.put(commandName, commandDetail);
                    }
                    deviceEntry.put("commands", filteredCommands);
                }
            } else {
                // Device config not found — return a stub so the backend still sees the entry
                deviceEntry.put("deviceName", "unknown");
                deviceEntry.put("commands",   new Document());
            }

            authorizedDevices.put(gatewayDeviceId, deviceEntry);
        }

        responseDoc.put("status",           "success");
        responseDoc.put("message",          "Retrieved backend authorizations successfully.");
        responseDoc.put("authorizedDevices", authorizedDevices);
        logger.info("Retrieved backend authorizations successfully.");
        return responseDoc;
    }
}