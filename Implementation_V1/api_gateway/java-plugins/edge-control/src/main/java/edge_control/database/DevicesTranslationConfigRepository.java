package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import edge_control.device.config.DeviceConfig;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.json.JSONObject;

/**
 * Repository for persisting and retrieving DeviceConfig objects from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "devices" collection in MongoDB.
 * - Converts between MongoDB Documents and DeviceConfig objects.
 * - Supports fetching all device configurations and upserting configurations.
 */
public class DevicesTranslationConfigRepository {

    private final MongoCollection<Document> collection;

    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public DevicesTranslationConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("devices_translation_config");
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
     * Saves or updates a DeviceConfig in MongoDB.
     * If the config has no deviceId, a new document is inserted and the generated id is returned.
     * If the config has a deviceId, the existing document is replaced.
     *
     * @param config the DeviceConfig to persist
     * @return the deviceId (MongoDB _id as hex string)
     */
    public String save(DeviceConfig config) {

        if (config.getDeviceId() == null) {
            // INSERT
            Document doc = new Document(config.getConfig().toMap());
            collection.insertOne(doc);

            ObjectId id = doc.getObjectId("_id");
            return id.toHexString();
        } else {
            // UPDATE
            ObjectId id = new ObjectId(config.getDeviceId());

            Document doc = new Document(config.getConfig().toMap());
            doc.put("_id", id);

            collection.replaceOne(
                    Filters.eq("_id", id),
                    doc
            );

            return config.getDeviceId();
        }
    }


}
