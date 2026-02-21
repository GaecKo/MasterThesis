package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
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
    private final MongoCollection<Document> backendAuthorizationCollection;

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
        this.backendAuthorizationCollection = db.getCollection("backendAuthorizations");
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
    * Adds backend authorization details to the database.
    *
    * @param requestBody the request body containing backend ID and authorization details
    * @return true if the operation was successful, false otherwise
    */
    public boolean addBackendAuthorization(Document requestBody) {

        String backendId = requestBody.getString("gatewayBackendId");
        Document newAuths = (Document) requestBody.get("listOfAuthorizations");

        if (backendId == null || newAuths == null) {
            return false;
        }

        // Build dynamic update for each device
        Document addToSetDoc = new Document();

        for (String deviceId : newAuths.keySet()) {
            Object commandsObj = newAuths.get(deviceId);

            if (commandsObj instanceof List<?>) {
                List<?> commands = (List<?>) commandsObj;

                addToSetDoc.put(
                        "listOfAuthorizations." + deviceId,
                        new Document("$each", commands)
                );
            }
        }

        Document update = new Document("$addToSet", addToSetDoc);

        // Upsert = insert if not exists
        backendAuthorizationCollection.updateOne(
                new Document("gatewayBackendId", backendId),
                update,
                new com.mongodb.client.model.UpdateOptions().upsert(true)
        );

        return true;
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
     * @param gatewayBackendId the backend ID
     * @param apiKey           the plain text API key to validate
     * @return true if the API key hash matches, false otherwise
     */
    public boolean validateApiKey(String gatewayBackendId, String apiKey) {
        if (gatewayBackendId == null || apiKey == null) {
            return false;
        }

        Document backendDoc = backendConfigCollection.find(
                new Document("gatewayBackendId", gatewayBackendId)
        ).first();

        if (backendDoc == null) {
            return false;
        }

        String storedApiKeyHash = backendDoc.getString("apiKeyHash");
        if (storedApiKeyHash == null) {
            return false;
        }

        String providedApiKeyHash = hashApiKey(apiKey);

        byte[] storedHashBytes = Base64.getDecoder().decode(storedApiKeyHash);
        byte[] providedHashBytes = Base64.getDecoder().decode(providedApiKeyHash);

        return MessageDigest.isEqual(storedHashBytes, providedHashBytes);
    }

}
