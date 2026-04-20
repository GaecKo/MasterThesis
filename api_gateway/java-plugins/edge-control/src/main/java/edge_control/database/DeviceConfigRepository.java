package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import edge_control.exceptions.IllegalOperation;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Repository for managing device communication config entries in MongoDB.
 * Handles batch creation (with API key generation per device), update, deletion, and key validation.
 */
public class DeviceConfigRepository {

    private final MongoCollection<Document> deviceConfigCollection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Holds the result of a single device creation — the generated ID and plain-text API key.
     * The plain-text key is returned once to the caller and never stored; only its hash is persisted.
     *
     * @param gatewayDeviceId Unique ID assigned to the new device
     * @param apiKey          Plain-text API key to return to the device admin
     */
    public record DeviceCreationResult(String gatewayDeviceId, String apiKey) {}

    /**
     * Connects to the "deviceConfig" collection in the edge_control database.
     */
    public DeviceConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.deviceConfigCollection = db.getCollection("deviceConfig");
    }

    // | ================= Read operations ================= |

    /**
     * @param requestBody Must contain 'gatewayDeviceId'
     * @return True if a document with that ID exists
     */
    public boolean deviceExists(Document requestBody) {
        String gatewayDeviceId = requestBody.getString("gatewayDeviceId");
        if (gatewayDeviceId == null) return false;
        return deviceConfigCollection.find(
                new Document("gatewayDeviceId", gatewayDeviceId)).first() != null;
    }

    /**
     * @return All device config documents in the collection
     */
    public List<Document> findAll() {
        return deviceConfigCollection.find().into(new ArrayList<>());
    }

    /**
     * Returns the config for a device, with sensitive fields stripped.
     *
     * @param gatewayDeviceId ID of the device to find
     * @return Device document without 'apiKeyHash' or '_id', or null if not found
     */
    public Document findDeviceById(String gatewayDeviceId) {
        if (gatewayDeviceId == null) return null;

        Document doc = deviceConfigCollection.find(
                new Document("gatewayDeviceId", gatewayDeviceId)).first();
        if (doc != null) {
            // Strip internal fields before returning to callers
            doc.remove("apiKeyHash");
            doc.remove("_id");
        }
        return doc;
    }

    // | ================= Write operations ================= |

    /**
     * Creates a config entry for each device in 'listOfDevices'.
     * Each device receives a unique ID and a secure 256-bit API key.
     * Only the hash of each key is stored.
     *
     * @param config Must contain 'listOfDevices' (list of device info Documents)
     * @return Map of deviceName to DeviceCreationResult (ID + plain-text key)
     */
    public HashMap<String, DeviceCreationResult> createDeviceConfig(Document config) {
        Iterable<Document> listOfDevices = config.getList("listOfDevices", Document.class);
        HashMap<String, DeviceCreationResult> responseMap = new HashMap<>();

        for (Document deviceInfo : listOfDevices) {
            String gatewayDeviceId = "device_" + UUID.randomUUID();
            String apiKey          = generateApiKey();

            // Store only the hash so a database breach does not expose usable keys
            String hashedApiKey = hashApiKey(apiKey);

            Document finalDoc = new Document();
            finalDoc.put("gatewayDeviceId", gatewayDeviceId);
            finalDoc.put("apiKeyHash", hashedApiKey);
            finalDoc.putAll(deviceInfo);

            deviceConfigCollection.insertOne(finalDoc);
            responseMap.put(deviceInfo.get("deviceName").toString(),
                    new DeviceCreationResult(gatewayDeviceId, apiKey));
        }

        return responseMap;
    }

    /**
     * Updates an existing device config entry.
     *
     * Nested objects are flattened to dot notation so MongoDB performs a deep merge
     * rather than replacing the entire object. Fields listed in 'fieldsToRemove' are unset.
     *
     * Example: {"commands": {"setBatteryOperation": {"name": "test"}}}
     * is stored as: {"commands.setBatteryOperation.name": "test"} via $set
     *
     * @param requestBody Must contain 'gatewayDeviceId'; may contain 'fieldsToRemove'
     * @return True if the update succeeded, false if ID is missing or nothing to update
     */
    public boolean updateDeviceConfig(Document requestBody) {
        String gatewayDeviceId = requestBody.getString("gatewayDeviceId");
        if (gatewayDeviceId == null) return false;

        Document setDoc   = new Document();
        Document unsetDoc = new Document();

        // Build $unset from the optional fieldsToRemove list
        List<String> fieldsToRemove = requestBody.getList("fieldsToRemove", String.class);
        if (fieldsToRemove != null) {
            for (String field : fieldsToRemove) {
                unsetDoc.put(field, "");
            }
        }

        // Build $set from all other fields; nested Documents are flattened to dot notation
        for (String key : requestBody.keySet()) {
            if (!key.equals("gatewayDeviceId") && !key.equals("fieldsToRemove")) {
                Object value = requestBody.get(key);
                if (value instanceof Document nested) {
                    // Flatten nested objects so $set does a deep merge instead of replacing the parent
                    flattenDocument(key, nested, setDoc);
                } else {
                    setDoc.put(key, value);
                }
            }
        }

        if (setDoc.isEmpty() && unsetDoc.isEmpty()) return false;

        try {
            Document updateOperation = new Document();
            if (!setDoc.isEmpty())   updateOperation.put("$set",   setDoc);
            if (!unsetDoc.isEmpty()) updateOperation.put("$unset", unsetDoc);

            deviceConfigCollection.updateOne(
                    new Document("gatewayDeviceId", gatewayDeviceId),
                    updateOperation);
            return true;
        } catch (Exception e) {
            logger.error("Failed to update device config for " + gatewayDeviceId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the config document for a device.
     *
     * @param requestBody Must contain 'gatewayDeviceId'
     * @return True if the delete succeeded, false if ID is missing
     */
    public boolean deleteDeviceConfig(Document requestBody) {
        String gatewayDeviceId = requestBody.getString("gatewayDeviceId");
        if (gatewayDeviceId == null) return false;

        try {
            deviceConfigCollection.deleteOne(
                    new Document("gatewayDeviceId", gatewayDeviceId));
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete device config for " + gatewayDeviceId + ": " + e.getMessage());
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
    private String hashApiKey(String apiKey) {
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
     * Looks up the device that owns the given API key by comparing its hash.
     *
     * @param apiKey Plain-text API key from the request header
     * @return The gatewayDeviceId if the key matches
     * @throws IllegalOperation If apiKey is null or if not found
     */
    public String validateApiKey(String apiKey) throws IllegalOperation {
        if (apiKey == null) {
            throw new IllegalOperation("API key cannot be null");
        }

        String hashedApiKey = hashApiKey(apiKey);
        Document deviceDoc = deviceConfigCollection.find(
                new Document("apiKeyHash", hashedApiKey)).first();

        if (deviceDoc == null) {
            throw new IllegalOperation("Invalid API key");
        }

        return deviceDoc.getString("gatewayDeviceId");
    }

    // | ================= Internal helpers ================= |

    /**
     * Recursively flattens a nested Document into dot-notation keys.
     * This enables MongoDB $set to perform a deep merge rather than replacing the parent object.
     *
     * Example: {commands: {setBatteryOperation: {name: "test"}}}
     * Becomes: {"commands.setBatteryOperation.name": "test"}
     *
     * @param prefix  Current dot-notation path prefix
     * @param document Nested document to flatten
     * @param result   Output document accumulating flattened entries
     */
    private void flattenDocument(String prefix, Document document, Document result) {
        for (String key : document.keySet()) {
            Object value  = document.get(key);
            String newKey = prefix + "." + key;
            if (value instanceof Document nested) {
                // Recurse into deeper nesting levels
                flattenDocument(newKey, nested, result);
            } else {
                result.put(newKey, value);
            }
        }
    }
}