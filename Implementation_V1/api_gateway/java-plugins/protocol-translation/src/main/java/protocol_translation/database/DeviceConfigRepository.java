package protocol_translation.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import protocol_translation.device.config.DeviceConfig;

/**
 * Repository for persisting and retrieving DeviceConfig objects from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "devices" collection in MongoDB.
 * - Converts between MongoDB Documents and DeviceConfig objects.
 * - Supports fetching all device configurations and upserting configurations.
 */
public class DeviceConfigRepository {

    private final MongoCollection<Document> collection;

    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public DeviceConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase();
        this.collection = db.getCollection("devices");
    }

    /**
     * Retrieves all device configurations from the collection.
     *
     * @return a list of all DeviceConfig objects stored in the database
     */
    public List<DeviceConfig> findAll() {
        List<DeviceConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            result.add(fromDocument(doc));
        }
        return result;
    }

    /**
     * Converts a MongoDB Document into a DeviceConfig object.
     *
     * @param doc the MongoDB document representing a device
     * @return the corresponding DeviceConfig object
     */
    private DeviceConfig fromDocument(Document doc) {
        return new DeviceConfig(
                doc.getString("deviceId"),
                doc.getString("adapter"),
                new JSONObject(doc.toJson())
        );
    }

    /**
     * Saves or updates a DeviceConfig in the database.
     * If a document with the same deviceId exists, it is replaced; otherwise, it is inserted.
     *
     * @param config the DeviceConfig to persist
     */
    public void save(DeviceConfig config) {
        Document doc = new Document(config.getConfig().toMap());
        collection.replaceOne(
                Filters.eq("deviceId", config.getDeviceId()),
                doc,
                new ReplaceOptions().upsert(true)
        );
    }

}
