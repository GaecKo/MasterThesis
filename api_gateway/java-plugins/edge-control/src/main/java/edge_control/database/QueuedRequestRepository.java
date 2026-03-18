package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.DeleteResult;
import edge_control.database.MongoClientProvider;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists and retrieves queued requests awaiting delivery to a device.
 * Collection: EdgeControlQueuing.queuedRequests
 *
 * TODO: define QueuedRequest model when the queuing worker is implemented.
 */
public class QueuedRequestRepository {

    private static final String DB_NAME         = "edge_control_queuing";
    private static final String COLLECTION_NAME = "queuedRequests";

    private final MongoCollection<Document> collection;
    private final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    public QueuedRequestRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase(DB_NAME);
        this.collection = db.getCollection(COLLECTION_NAME);
    }

    // TODO: implement save(QueuedRequest), findPendingByDeviceId(), delete() when
    //       the queuing worker is built. Collection is created here so it exists
    //       in MongoDB from the moment queuing is onboarded for a device.

    public boolean deleteAllForDevice(String gatewayDeviceId) throws EdgeControlException {
        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException(
                    "Cannot delete queued requests: gatewayDeviceId is null or blank");
        }
        DeleteResult result = collection.deleteMany(
                Filters.eq("gatewayDeviceId", gatewayDeviceId));
        return result.getDeletedCount() > 0;
    }

    public List<Document> findAllForDevice(String gatewayDeviceId) {
        List<Document> result = new ArrayList<>();
        collection.find(Filters.eq("gatewayDeviceId", gatewayDeviceId))
                .into(result);
        return result;
    }
}
