package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.config.DeviceQueueConfig;
import org.bson.Document;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists and retrieves per-device queuing configuration.
 * Collection: edge_control_queuing.deviceQueueConfig
 */
public class QueueConfigRepository {

    private static final String DB_NAME         = "edge_control_queuing";
    private static final String COLLECTION_NAME = "deviceQueueConfig";

    private final MongoCollection<Document> collection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Connects to the "deviceQueueConfig" collection in the edge_control_queuing database.
     */
    public QueueConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase(DB_NAME);
        this.collection = db.getCollection(COLLECTION_NAME);
    }

    // | ================= Read operations ================= |

    /**
     * Loads all queue configs from the collection.
     * Corrupt documents are logged and skipped rather than aborting the load.
     *
     * @return List of all valid DeviceQueueConfig objects
     */
    public List<DeviceQueueConfig> findAll() {
        List<DeviceQueueConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            try {
                result.add(new DeviceQueueConfig(new JSONObject(doc.toJson())));
            } catch (CorruptedConfiguration e) {
                // Skip the bad document but log it — one corrupt entry should not block the rest
                logger.error("Corrupted queue config in DB: " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Finds the queue config for a specific device.
     *
     * @param gatewayDeviceId Device to look up
     * @return DeviceQueueConfig if found and valid, null otherwise
     */
    public DeviceQueueConfig findByDeviceId(String gatewayDeviceId) {
        Document doc = collection.find(
                Filters.eq("gatewayDeviceId", gatewayDeviceId)).first();
        if (doc == null) return null;
        try {
            return new DeviceQueueConfig(new JSONObject(doc.toJson()));
        } catch (CorruptedConfiguration e) {
            logger.error("Corrupted queue config for device " + gatewayDeviceId
                    + ": " + e.getMessage());
            return null;
        }
    }

    // | ================= Write operations ================= |

    /**
     * Upserts a device queue config.
     * If a document with the same gatewayDeviceId exists it is replaced; otherwise a new one is inserted.
     *
     * @param config DeviceQueueConfig to persist
     * @throws EdgeControlException If gatewayDeviceId is missing
     */
    public void save(DeviceQueueConfig config) throws EdgeControlException {
        String gatewayDeviceId = config.getGatewayDeviceId();
        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException(
                    "Cannot save queue config: gatewayDeviceId is null or blank");
        }

        Document doc = new Document(config.toDocument().toMap());
        collection.replaceOne(
                Filters.eq("gatewayDeviceId", gatewayDeviceId),
                doc,
                new ReplaceOptions().upsert(true));
    }

    /**
     * Deletes the queue config for a device.
     *
     * @param gatewayDeviceId Device whose config to delete
     * @return True if a document was found and deleted, false if no match existed
     * @throws EdgeControlException If gatewayDeviceId is null or blank
     */
    public boolean delete(String gatewayDeviceId) throws EdgeControlException {
        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException(
                    "Cannot delete queue config: gatewayDeviceId is null or blank");
        }
        DeleteResult result = collection.deleteOne(
                Filters.eq("gatewayDeviceId", gatewayDeviceId));
        return result.getDeletedCount() > 0;
    }
}