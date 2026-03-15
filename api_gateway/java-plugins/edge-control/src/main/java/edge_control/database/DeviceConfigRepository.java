package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Repository for creation and modification of a device entry from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "deviceConfig" collection in MongoDB.
 * - Creates an entry in the collection for a device along with its communication info.
 */
public class DeviceConfigRepository {

    private final MongoCollection<Document> deviceConfigCollection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Inner class to hold device creation response (ID and API key)
     */
    public static class DeviceCreationResult {
        public final String gatewayDeviceId;
        public final String apiKey;

        public DeviceCreationResult(String gatewaydeviceId, String apiKey) {
            this.gatewayDeviceId = gatewaydeviceId;
            this.apiKey = apiKey;
        }
    }

    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public DeviceConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.deviceConfigCollection = db.getCollection("deviceConfig");
    }

    /**
     * Checks if a device with the given ID exists in the database.
     * @param requestBody the request body containing the gatewayDeviceId to check
     * @return true if the device exists, false otherwise
     */
    public boolean deviceExists(Document requestBody) {
        String gatewayDeviceId = requestBody.getString("gatewayDeviceId");
        if (gatewayDeviceId == null) {
            return false;
        }

        Document deviceDoc = deviceConfigCollection.find(new Document("gatewayDeviceId", gatewayDeviceId)).first();
        return deviceDoc != null;
    }

    public List<Document> findAll() {
        return deviceConfigCollection.find().into(new ArrayList<>());
    }

    /**
     * Create the device configuration in the database.
     * Generates a unique gatewayDeviceId and a secure API key.
     * The API key is hashed before storing in the database for security.
     *
     * @param config the device configuration to be created
     * @return deviceCreationResult containing both gatewaydeviceId and apiKey (plain text to send to device)
     */
    public HashMap<String, DeviceCreationResult> createDeviceConfig(Document config) {
        Iterable<Document> listOfDevices = config.getList("listOfDevices", Document.class);

        HashMap<String, DeviceCreationResult> responseMap = new HashMap<>();

        for (Document deviceInfo : listOfDevices) {
            // Generate a unique gatewaydeviceId
            String gatewayDeviceId = "device_" + UUID.randomUUID();

            // Generate a secure API key
            String apiKey = generateApiKey();

            // Hash the API key before storing in database
            String hashedApiKey = hashApiKey(apiKey);

            // Create a MongoDB document with the gatewaydeviceId, hashed API key, and the provided config
            Document finalDoc = new Document();
            finalDoc.put("gatewayDeviceId", gatewayDeviceId);
            finalDoc.put("apiKeyHash", hashedApiKey);
            finalDoc.putAll(deviceInfo);

            responseMap.put(deviceInfo.get("deviceName").toString(), new DeviceCreationResult(gatewayDeviceId, apiKey));

            // Insert the document into MongoDB
            this.deviceConfigCollection.insertOne(finalDoc);
        }

        return responseMap;

    }

    /**
     * Update an existing device configuration in the database based on the provided gatewayDeviceId.
     * Supports three operations:
     * 1. Add/Update: Include fields directly in the request body (uses deep merge with dot notation)
     * 2. Remove: Include field paths in "fieldsToRemove" array (removes entire fields)
     * 3. Replace nested object: Send complete object to replace it entirely
     *
     * Usage:
     * - Update nested field: {"gatewayDeviceId": "xxx", "commands": {"setBatteryOperation": {"params": {"activePower": {...}}}}}
     * - Remove field: {"gatewayDeviceId": "xxx", "fieldsToRemove": ["commands.setBatteryOperation.params.maxRate"]}
     * - Replace object: {"gatewayDeviceId": "xxx", "commands": {"newCommand": {...}}} (replaces entire commands object)
     *
     * @param requestBody the request body containing gatewayDeviceId, update fields, and optional fieldsToRemove
     * @return true if the update was successful, false otherwise
     */
    public boolean updateDeviceConfig(Document requestBody) {
        String gatewayDeviceId = requestBody.getString("gatewayDeviceId");

        if (gatewayDeviceId == null) {
            return false;
        }

        Document setDoc = new Document();
        Document unsetDoc = new Document();

        // Process fieldsToRemove first (if present)
        Object fieldsToRemove = requestBody.get("fieldsToRemove");
        if (fieldsToRemove instanceof java.util.List) {
            java.util.List<String> removeList = (java.util.List<String>) fieldsToRemove;
            for (String field : removeList) {
                unsetDoc.put(field, "");
            }
        }

        // Process regular fields for update/add
        for (String key : requestBody.keySet()) {
            if (!key.equals("gatewayDeviceId") && !key.equals("fieldsToRemove")) {
                Object value = requestBody.get(key);

                // Handle nested objects using dot notation for deep merge
                if (value instanceof Document) {
                    flattenDocument(key, (Document) value, setDoc);
                } else {
                    setDoc.put(key, value);
                }
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

            deviceConfigCollection.updateOne(
                new Document("gatewayDeviceId", gatewayDeviceId),
                updateOperation
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Recursively flattens a nested Document into dot notation.
     * This enables MongoDB to perform deep merging when using $set.
     *
     * Example: {commands: {setBatteryOperation: {name: "test"}}}
     * Becomes: {"commands.setBatteryOperation.name": "test"}
     *
     * @param prefix the current path prefix
     * @param document the document to flatten
     * @param result the result document with flattened keys
     */
    private void flattenDocument(String prefix, Document document, Document result) {
        for (String key : document.keySet()) {
            Object value = document.get(key);
            String newKey = prefix + "." + key;

            if (value instanceof Document) {
                flattenDocument(newKey, (Document) value, result);
            } else {
                result.put(newKey, value);
            }
        }
    }

    /**
     * Delete a device configuration from the database based on the provided gatewayBackendId.
     * Removes the corresponding document from the "deviceConfig" collection.
     * @param requestBody the request body containing the gatewayDeviceId to delete
     * @return true if the deletion was successful, false otherwise
     */
    public boolean deleteDeviceConfig(Document requestBody) {
        String gatewayDeviceId = requestBody.getString("gatewayDeviceId");
        if (gatewayDeviceId == null) {
            return false;
        }

        try {
            deviceConfigCollection.deleteOne(new Document("gatewayDeviceId", gatewayDeviceId));
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
     * Finds a device configuration by its gatewayDeviceId.
     *
     * @param gatewayDeviceId the ID of the device to find
     * @return the device Document (without apiKeyHash), or null if not found
     */
    public Document findDeviceById(String gatewayDeviceId) {
        if (gatewayDeviceId == null) {
            return null;
        }
        Document doc = deviceConfigCollection.find(new Document("gatewayDeviceId", gatewayDeviceId)).first();
        if (doc != null) {
            doc.remove("apiKeyHash");
            doc.remove("_id");
        }
        return doc;
    }

    /**
     * Validates an API key for a given device ID.
     * Compares the hash of the provided API key with the stored hash.
     *
     * @param apiKey the plain text API key to validate
     * @return gatewayDeviceId if the API key hash matches
     */
    public String validateApiKey(String apiKey) {
        if (apiKey == null) {
            return "API key cannot be null";
        }

        String hashedApiKey = hashApiKey(apiKey);
        Document backendDoc = deviceConfigCollection.find(new Document("apiKeyHash", hashedApiKey)).first();

        if (backendDoc != null) {
            return backendDoc.getString("gatewayDeviceId");
        } else {
            return "Invalid API key";
        }
    }

}
