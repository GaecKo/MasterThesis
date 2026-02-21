package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

/**
 * Repository for creation and modification of a device entry from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "deviceConfig" collection in MongoDB.
 * - Creates an entry in the collection for a device along with its communication info.
 */
public class DeviceConfigRepository {

    private final MongoCollection<Document> deviceConfigCollection;

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
            finalDoc.put("gatewaydeviceId", gatewayDeviceId);
            finalDoc.put("apiKeyHash", hashedApiKey);
            finalDoc.putAll(deviceInfo);

            responseMap.put(deviceInfo.get("deviceName").toString(), new DeviceCreationResult(gatewayDeviceId, apiKey));

            // Insert the document into MongoDB
            this.deviceConfigCollection.insertOne(finalDoc);
        }

        return responseMap;

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
     * Validates an API key for a given device ID.
     * Compares the hash of the provided API key with the stored hash.
     *
     * @param gatewayDeviceId the device ID
     * @param apiKey           the plain text API key to validate
     * @return true if the API key hash matches, false otherwise
     */
    public boolean validateApiKey(String gatewayDeviceId, String apiKey) {
        if (gatewayDeviceId == null || apiKey == null) {
            return false;
        }

        Document deviceDoc = deviceConfigCollection.find(
                new Document("gatewayDeviceId", gatewayDeviceId)
        ).first();

        if (deviceDoc == null) {
            return false;
        }

        String storedApiKeyHash = deviceDoc.getString("apiKeyHash");
        if (storedApiKeyHash == null) {
            return false;
        }

        String providedApiKeyHash = hashApiKey(apiKey);

        byte[] storedHashBytes = Base64.getDecoder().decode(storedApiKeyHash);
        byte[] providedHashBytes = Base64.getDecoder().decode(providedApiKeyHash);

        return MessageDigest.isEqual(storedHashBytes, providedHashBytes);
    }

}
