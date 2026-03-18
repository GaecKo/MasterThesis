package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.DeleteResult;
import edge_control.database.MongoClientProvider;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.model.QueuedRequest;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists and retrieves queued requests awaiting delivery to a device.
 * Collection: EdgeControlQueuing.queuedRequests
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

    public void save(QueuedRequest request) throws EdgeControlException {
        if (request.getGatewayDeviceId() == null || request.getGatewayDeviceId().isBlank()) {
            throw new EdgeControlException(
                    "Cannot save queued request: gatewayDeviceId is null or blank");
        }
        collection.insertOne(request.toDocument());
    }

    public List<QueuedRequest> findAllForDevice(String gatewayDeviceId) {
        List<QueuedRequest> result = new ArrayList<>();
        for (Document doc : collection.find(Filters.eq("gatewayDeviceId", gatewayDeviceId))) {
            result.add(new QueuedRequest(doc));
        }
        return result;
    }

    public boolean delete(String id) throws EdgeControlException {
        if (id == null || id.isBlank()) {
            throw new EdgeControlException(
                    "Cannot delete queued request: id is null or blank");
        }
        DeleteResult result = collection.deleteOne(Filters.eq("id", id));
        return result.getDeletedCount() > 0;
    }

    public boolean deleteAllForDevice(String gatewayDeviceId) throws EdgeControlException {
        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException(
                    "Cannot delete queued requests: gatewayDeviceId is null or blank");
        }
        DeleteResult result = collection.deleteMany(
                Filters.eq("gatewayDeviceId", gatewayDeviceId));
        return result.getDeletedCount() > 0;
    }
}
