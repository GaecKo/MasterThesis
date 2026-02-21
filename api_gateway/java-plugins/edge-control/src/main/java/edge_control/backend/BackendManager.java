package edge_control.backend;

import edge_control.database.BackendConfigRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

public class BackendManager {

    private static BackendManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();

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
     * Add authorizations for a backend.
     *
     * @param requestBody the body fo the request containing the backend ID and authorization details
     * @return true if addition was successful, false otherwise
     */
    public Document addBackendAuthorizationConfig(String requestBody) {
        boolean succes = backendConfig.addBackendAuthorization(Document.parse(requestBody));
        Document responseDoc = new Document();

        if (succes) {
            responseDoc.put("status", "success");
            logger.info("Added backend authorization");
        } else {
            responseDoc.put("status", "failure");
            logger.error("Failed to add backend authorization");
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
