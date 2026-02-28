package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
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
            result.add(new DeviceConfig(new JSONObject(doc.toJson())));
        }
        return result;
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

        if (configJson.getString("gatewayDeviceId") == null) {
            throw new EdgeControlException("Internal error: gatewayDeviceId must be provided in 'save(RequestObject)', but is null");
        }

        String gatewayDeviceId = configJson.getString("gatewayDeviceId");

        Document doc = new Document(configJson.toMap());

        collection.replaceOne(
                Filters.eq("gatewayDeviceId", gatewayDeviceId),
                doc,
                new ReplaceOptions().upsert(true)
        );

    }



}
