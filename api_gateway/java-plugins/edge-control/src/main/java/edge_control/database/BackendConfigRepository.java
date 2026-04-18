package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Repository for managing backend communication config entries in MongoDB.
 * Handles creation (with API key generation), update, deletion, and key validation.
 */
public class BackendConfigRepository {

    private final MongoCollection<Document> backendConfigCollection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Holds the result of a backend creation — the generated ID and plain-text API key.
     * The plain-text key is returned once to the caller and never stored; only its hash is persisted.
     *
     * @param gatewayBackendId Unique ID assigned to the new backend
     * @param apiKey           Plain-text API key to return to the backend admin
     */
    public record BackendCreationResult(String gatewayBackendId, String apiKey) {}

    /**
     * Connects to the "backendConfig" collection in the edge_control database.
     */
    public BackendConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.backendConfigCollection = db.getCollection("backendConfig");
    }

    // | ================= Read operations ================= |

    /**
     * @param requestBody Must contain 'gatewayBackendId'
     * @return True if a document with that ID exists
     */
    public boolean backendExists(Document requestBody) {
        String gatewayBackendId = requestBody.getString("gatewayBackendId");
        if (gatewayBackendId == null) return false;
        return backendConfigCollection.find(
                new Document("gatewayBackendId", gatewayBackendId)).first() != null;
    }

    /**
     * @return All backend config documents in the collection
     */
    public List<Document> findAll() {
        return backendConfigCollection.find().into(new ArrayList<>());
    }

    /**
     * Returns the config for a backend, with sensitive fields stripped.
     *
     * @param gatewayBackendId ID of the backend to find
     * @return Backend document without 'apiKeyHash' or '_id', or null if not found
     */
    public Document findBackendById(String gatewayBackendId) {
        if (gatewayBackendId == null) return null;

        Document doc = backendConfigCollection.find(
                new Document("gatewayBackendId", gatewayBackendId)).first();
        if (doc != null) {
            // Strip internal fields before returning to callers
            doc.remove("apiKeyHash");
            doc.remove("_id");
        }
        return doc;
    }

    // | ================= Write operations ================= |

    /**
     * Creates a new backend config entry. Generates a unique ID and a secure 256-bit API key.
     * The plain-text key is returned once and never stored — only its SHA-256 hash is persisted.
     *
     * @param config Additional config fields to merge into the document
     * @return BackendCreationResult with the assigned ID and the plain-text API key
     */
    public BackendCreationResult createBackendConfig(Document config) {
        String gatewayBackendId = "backend_" + UUID.randomUUID();
        String apiKey           = generateApiKey();

        // Store only the hash so a database breach does not expose usable keys
        String hashedApiKey = hashApiKey(apiKey);

        Document finalDoc = new Document();
        finalDoc.put("gatewayBackendId", gatewayBackendId);
        finalDoc.put("apiKeyHash", hashedApiKey);
        finalDoc.putAll(config);

        backendConfigCollection.insertOne(finalDoc);

        return new BackendCreationResult(gatewayBackendId, apiKey);
    }

    /**
     * Updates an existing backend config entry.
     * Regular fields in the body are set via $set; fields listed in 'fieldsToRemove' are unset.
     *
     * @param requestBody Must contain 'gatewayBackendId'; may contain 'fieldsToRemove'
     * @return True if the update succeeded, false if ID is missing or nothing to update
     */
    public boolean updateBackendConfig(Document requestBody) {
        String gatewayBackendId = requestBody.getString("gatewayBackendId");
        if (gatewayBackendId == null) return false;

        Document setDoc   = new Document();
        Document unsetDoc = new Document();

        // Build $unset from the optional fieldsToRemove list
        List<String> fieldsToRemove = requestBody.getList("fieldsToRemove", String.class);
        if (fieldsToRemove != null) {
            for (String field : fieldsToRemove) {
                unsetDoc.put(field, "");
            }
        }

        // Build $set from all other fields, excluding internal routing keys
        for (String key : requestBody.keySet()) {
            if (!key.equals("gatewayBackendId") && !key.equals("fieldsToRemove")) {
                setDoc.put(key, requestBody.get(key));
            }
        }

        if (setDoc.isEmpty() && unsetDoc.isEmpty()) {
            return false;
        }

        try {
            Document updateOperation = new Document();
            if (!setDoc.isEmpty())   updateOperation.put("$set",   setDoc);
            if (!unsetDoc.isEmpty()) updateOperation.put("$unset", unsetDoc);

            backendConfigCollection.updateOne(
                    new Document("gatewayBackendId", gatewayBackendId),
                    updateOperation);
            return true;
        } catch (Exception e) {
            logger.error("Failed to update backend config for " + gatewayBackendId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the config document for a backend.
     *
     * @param requestBody Must contain 'gatewayBackendId'
     * @return True if the delete succeeded, false if ID is missing
     */
    public boolean deleteBackendConfig(Document requestBody) {
        String gatewayBackendId = requestBody.getString("gatewayBackendId");
        if (gatewayBackendId == null) return false;

        try {
            backendConfigCollection.deleteOne(
                    new Document("gatewayBackendId", gatewayBackendId));
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete backend config for " + gatewayBackendId + ": " + e.getMessage());
            return false;
        }
    }

    // | ================= API key helpers ================= |

    /**
     * Generates a cryptographically secure 256-bit API key, Base64 URL-encoded.
     *
     * @return Plain-text API key (to be returned to the caller, never stored)
     */
    private String generateApiKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] apiKeyBytes = new byte[32];
        secureRandom.nextBytes(apiKeyBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(apiKeyBytes);
    }

    /**
     * Hashes an API key using SHA-256 for safe storage.
     *
     * @param apiKey Plain-text API key
     * @return Base64-encoded SHA-256 hash
     */
    public String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available in all Java SE environments
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Looks up the backend that owns the given API key by comparing its hash.
     *
     * @param apiKey Plain-text API key from the request header
     * @return The gatewayBackendId if the key matches, or "Invalid API key" if not found
     */
    public String validateApiKey(String apiKey) {
        if (apiKey == null) return "API key cannot be null";

        String hashedApiKey = hashApiKey(apiKey);
        Document backendDoc = backendConfigCollection.find(
                new Document("apiKeyHash", hashedApiKey)).first();

        return backendDoc != null
                ? backendDoc.getString("gatewayBackendId")
                : "Invalid API key";
    }
}