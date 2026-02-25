package edge_control.backend;

import edge_control.database.BackendAuthorizationsRepository;
import edge_control.database.BackendConfigRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

public class BackendManager {

    private static BackendManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();

    private final BackendAuthorizationsRepository backendAuthorizations = new BackendAuthorizationsRepository();

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
        responseDoc.put("apiKey", result.apiKey);
        responseDoc.put("gatewayBackendId", result.gatewayBackendId);
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
     * Deletes a backend entry and all related authorization entries from the database.
     * Removes the backend entry and all related authorization entries from the database.
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
     * Validates an API key for a backend.
     *
     * @param gatewayBackendId the backend ID
     * @param apiKey the API key to validate
     * @return true if the API key is valid, false otherwise
     */
    public boolean validateBackendApiKey(String gatewayBackendId, String apiKey) {
        return backendConfig.validateApiKey(gatewayBackendId, apiKey);
    }
}

