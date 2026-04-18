package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.config.DeviceConfig;
import org.bson.Document;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for persisting and retrieving DeviceConfig objects from MongoDB.
 * Converts between MongoDB Documents and DeviceConfig objects, and supports
 * upsert and delete by gatewayDeviceId.
 */
public class DevicesTranslationConfigRepository {

    private final MongoCollection<Document> collection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Connects to the "deviceTranslationConfig" collection in the edge_control database.
     */
    public DevicesTranslationConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.collection = db.getCollection("deviceTranslationConfig");
    }

    // | ================= Read operations ================= |

    /**
     * Loads all device translation configs from the collection.
     * Documents that fail to parse are logged and skipped rather than aborting the load.
     *
     * @return List of all valid DeviceConfig objects in the collection
     */
    public List<DeviceConfig> findAll() {
        List<DeviceConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            try {
                result.add(new DeviceConfig(new JSONObject(doc.toJson())));
            } catch (CorruptedConfiguration e) {
                // Skip the bad document but log it — a single corrupt entry should not block the rest
                logger.error(e.getMessage() + " - corrupted document in DB, doc ID: " + doc.getString("_id"));
            }
        }
        return result;
    }

    // | ================= Write operations ================= |

    /**
     * Upserts a device translation config.
     * If a document with the same gatewayDeviceId exists it is replaced; otherwise a new one is inserted.
     *
     * @param config DeviceConfig to persist
     * @throws EdgeControlException If gatewayDeviceId is missing or the write fails
     */
    public void save(DeviceConfig config) throws EdgeControlException {
        String gatewayDeviceId = config.getDeviceId();

        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException(
                    "gatewayDeviceId must be provided when saving a device config");
        }

        Document doc = new Document(config.getConfig().toMap());

        collection.replaceOne(
                Filters.eq("gatewayDeviceId", gatewayDeviceId),
                doc,
                new ReplaceOptions().upsert(true));
    }

    /**
     * Deletes the translation config for a device.
     *
     * @param gatewayDeviceId ID of the device to delete
     * @return True if a document was found and deleted, false if no match existed
     * @throws EdgeControlException If gatewayDeviceId is null or blank
     */
    public boolean delete(String gatewayDeviceId) throws EdgeControlException {
        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException(
                    "Cannot remove device configuration: gatewayDeviceId is null or blank");
        }

        DeleteResult result = collection.deleteOne(
                Filters.eq("gatewayDeviceId", gatewayDeviceId));

        return result.getDeletedCount() > 0;
    }
}