package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import edge_control.database.MongoClientProvider;
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
 * Collection: EdgeControlQueuing.deviceQueueConfig
 */
public class QueueConfigRepository {

    private static final String DB_NAME         = "edge_control_queuing";
    private static final String COLLECTION_NAME = "deviceQueueConfig";

    private final MongoCollection<Document> collection;
    private final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    public QueueConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase(DB_NAME);
        this.collection = db.getCollection(COLLECTION_NAME);
    }

    public List<DeviceQueueConfig> findAll() {
        List<DeviceQueueConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            try {
                result.add(new DeviceQueueConfig(new JSONObject(doc.toJson())));
            } catch (CorruptedConfiguration e) {
                logger.error("Corrupted queue config in DB: " + e.getMessage());
            }
        }
        return result;
    }

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