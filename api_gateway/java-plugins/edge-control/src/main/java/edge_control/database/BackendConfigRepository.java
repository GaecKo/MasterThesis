package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Repository for creation and modification of a backend entry from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "backendConfig" collection in MongoDB.
 * - Creates an entry in the collection for a backend along with its communication info.
 */
public class BackendConfigRepository {

    private final MongoCollection<Document> backendConfigCollection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Inner class to hold backend creation response (ID and API key)
     */
    public static class BackendCreationResult {
        public final String gatewayBackendId;
        public final String apiKey;

        public BackendCreationResult(String gatewayBackendId, String apiKey) {
            this.gatewayBackendId = gatewayBackendId;
            this.apiKey = apiKey;
        }
    }

    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public BackendConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.backendConfigCollection = db.getCollection("backendConfig");
    }

    /**
     * Create the backend configuration in the database.
     * Generates a unique gatewayBackendId and a secure API key.
     * The API key is hashed before storing in the database for security.
     *
     * @param config the backend configuration to be created
     * @return BackendCreationResult containing both gatewayBackendId and apiKey (plain text to send to backend)
     */
    public BackendCreationResult createBackendConfig(Document config) {
        // Generate a unique gatewayBackendId
        String gatewayBackendId = "backend_" + UUID.randomUUID();

        // Generate a secure API key
        String apiKey = generateApiKey();

        // Hash the API key before storing in database
        String hashedApiKey = hashApiKey(apiKey);

        // Create a MongoDB document with the gatewayBackendId, hashed API key, and the provided config
        Document finalDoc = new Document();
        finalDoc.put("gatewayBackendId", gatewayBackendId);
        finalDoc.put("apiKeyHash", hashedApiKey);
        finalDoc.putAll(config);

        // Insert the document into MongoDB
        this.backendConfigCollection.insertOne(finalDoc);

        return new BackendCreationResult(gatewayBackendId, apiKey);
    }

    /**
     * Update an existing backend configuration in the database based on the provided gatewayBackendId.
     * Modifies communication details for an existing backend.
     *
     * @param requestBody the body of the request containing the backend ID and updated configuration details
     * @return true if the update was successful, false otherwise
     */
    public boolean updateBackendConfig(Document requestBody) {
        String gatewayBackendId = requestBody.getString("gatewayBackendId");

        if (gatewayBackendId == null) {
            return false;
        }

        Document setDoc = new Document();
        Document unsetDoc = new Document();

        // Process fieldsToRemove first (if present)
        List<String>  fieldsToRemove = requestBody.getList("fieldsToRemove", String.class);
            for (String field : fieldsToRemove) {
                unsetDoc.put(field, "");
            }


        // Process regular fields for update/add
        for (String key : requestBody.keySet()) {
            if (!key.equals("gatewayBackendId") && !key.equals("fieldsToRemove")) {
                Object value = requestBody.get(key);
                setDoc.put(key, value);
            }
        }

        if (setDoc.isEmpty() && unsetDoc.isEmpty()) {
            return false; // No fields to update or remove
        }

        try {
            Document updateOperation = new Document();

            // Add $set operation if there are fields to update
            if (!setDoc.isEmpty()) {
                updateOperation.put("$set", setDoc);
            }

            // Add $unset operation if there are fields to remove
            if (!unsetDoc.isEmpty()) {
                updateOperation.put("$unset", unsetDoc);
            }

            backendConfigCollection.updateOne(
                    new Document("gatewayBackendId", gatewayBackendId),
                    updateOperation
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete a backend configuration from the database based on the provided gatewayBackendId.
     * Removes the corresponding document from the "backendConfig" collection.
     * @param requestBody the request body containing the gatewayBackendId to delete
     * @return true if the deletion was successful, false otherwise
     */
    public boolean deleteBackendConfig(Document requestBody) {
        String gatewayBackendId = requestBody.getString("gatewayBackendId");
        if (gatewayBackendId == null) {
            return false;
        }

        try {
            backendConfigCollection.deleteOne(new Document("gatewayBackendId", gatewayBackendId));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generates a secure API key using SecureRandom and Base64 encoding.
     * The key is 32 bytes (256 bits) encoded as a Base64 URL-safe string.
     *
     * @return a secure API key
     */
    private String generateApiKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] apiKeyBytes = new byte[32]; // 256-bit key
        secureRandom.nextBytes(apiKeyBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(apiKeyBytes);
    }

    /**
     * Hashes an API key using SHA-256.
     * This ensures only the hash is stored in the database, not the plain text key.
     *
     * @param apiKey the plain text API key
     * @return the SHA-256 hash of the API key
     */
    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Validates an API key for a given backend ID.
     * Compares the hash of the provided API key with the stored hash.
     *
     * @param apiKey the plain text API key to validate
     * @return gatewayBackendId if the API key hash matches
     */
    public String validateApiKey(String apiKey) {
        if (apiKey == null) {
            return "API key cannot be null";
        }

        String hashedApiKey = hashApiKey(apiKey);
        Document backendDoc = backendConfigCollection.find(new Document("apiKeyHash", hashedApiKey)).first();

        if (backendDoc != null) {
            return backendDoc.getString("gatewayBackendId");
        } else {
            return "Invalid API key";
        }
    }

}
