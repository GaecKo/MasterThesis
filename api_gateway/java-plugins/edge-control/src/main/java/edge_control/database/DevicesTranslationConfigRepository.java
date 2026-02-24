package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import edge_control.device_translation.config.DeviceConfig;
import edge_control.exceptions.EdgeControlException;
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
                doc.getString("deviceID"),
                doc.getString("adapter"),
                new JSONObject(doc.toJson())
        );
    }

    /**
     * Saves or updates a DeviceConfig in MongoDB.
     * If a document with the same deviceId exists, it is replaced.
     * Otherwise, a new document is inserted with that deviceId.
     *
     * @param config the DeviceConfig to persist
     * @return the deviceId
     */
    public void save(DeviceConfig config) throws EdgeControlException {

        if (config.getDeviceId() == null) {
            throw new EdgeControlException("Internal error: deviceId must be provided in 'save(DeviceConfig)', but deviceConfig.getDeviceId() is null");
        }

        ObjectId id = new ObjectId(config.getDeviceId());

        Document doc = new Document(config.getConfig().toMap());
        doc.put("_id", id);

        collection.replaceOne(
                Filters.eq("_id", id),
                doc,
                new ReplaceOptions().upsert(true)
        );

    }



}
