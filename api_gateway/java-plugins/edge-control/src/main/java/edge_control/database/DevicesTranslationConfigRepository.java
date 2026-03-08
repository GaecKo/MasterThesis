package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.config.DeviceConfig;
import edge_control.exceptions.EdgeControlException;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

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
    private final EdgeControlLogger logger = EdgeControlLogger.getInstance();
    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public DevicesTranslationConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.collection = db.getCollection("deviceTranslationConfig");
    }

    /**
     * Retrieves all device configurations from the collection.
     *
     * @return a list of all DeviceConfig objects stored in the database
     */
    public List<DeviceConfig> findAll() {
        List<DeviceConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            try {
                result.add(new DeviceConfig(new JSONObject(doc.toJson())));
            } catch (CorruptedConfiguration e) {
                logger.error(e.getMessage() + " - data corrupted in db! Doc ID: " + doc.getString("_id"));
            }
        }
        return result;
    }

    public boolean delete(String gatewayDeviceId) throws EdgeControlException {

        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException("Cannot remove device configuration: gatewayDeviceId is null or blank");
        }

        DeleteResult result = collection.deleteOne(
                Filters.eq("gatewayDeviceId", gatewayDeviceId)
        );

        return result.getDeletedCount() > 0;
    }

    /**
     * Saves or updates a object in MongoDB.
     * If a document with the same deviceId exists, it is replaced.
     * Otherwise, a new document is inserted with that deviceId.
     *
     * @param config the DeviceConfig object to persist
     * @return the deviceId
     */
    public void save(DeviceConfig config) throws EdgeControlException {

        JSONObject configJson = config.getConfig();
        String gatewayDeviceId = config.getDeviceId();

        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException("gatewayDeviceId must be provided when saving a device config");
        }

        Document doc = new Document(configJson.toMap());

        collection.replaceOne(
                Filters.eq("gatewayDeviceId", gatewayDeviceId),
                doc,
                new ReplaceOptions().upsert(true)
        );

    }



}
