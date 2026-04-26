package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.DeleteResult;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.model.QueuedRequest;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists and retrieves queued requests awaiting delivery to a device.
 * Collection: edge_control_queuing.queuedRequests
 */
public class QueuedRequestRepository {

    private static final String DB_NAME         = "edge_control_queuing";
    private static final String COLLECTION_NAME = "queuedRequests";

    private final MongoCollection<Document> collection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Connects to the "queuedRequests" collection in the edge_control_queuing database.
     */
    public QueuedRequestRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase(DB_NAME);
        this.collection = db.getCollection(COLLECTION_NAME);
    }

    // | ================= Write operations ================= |

    /**
     * Inserts a new queued request document.
     *
     * @param request Request to persist; must have a non-blank gatewayDeviceId
     * @throws EdgeControlException If gatewayDeviceId is missing
     */
    public void save(QueuedRequest request) throws EdgeControlException {
        if (request.getGatewayDeviceId() == null || request.getGatewayDeviceId().isBlank()) {
            throw new EdgeControlException(
                    "Cannot save queued request: gatewayDeviceId is null or blank");
        }
        collection.insertOne(request.toDocument());
    }

    /**
     * Deletes a single queued request by its ID.
     *
     * @param id Request ID to delete
     * @return True if a document was found and deleted
     * @throws EdgeControlException If id is null or blank
     */
    public boolean delete(String id) throws EdgeControlException {
        if (id == null || id.isBlank()) {
            throw new EdgeControlException(
                    "Cannot delete queued request: id is null or blank");
        }
        DeleteResult result = collection.deleteOne(Filters.eq("id", id));
        return result.getDeletedCount() > 0;
    }

    /**
     * Deletes all queued requests for a device.
     * Called when a device is offboarded.
     *
     * @param gatewayDeviceId Device whose requests to delete
     * @return True if at least one document was deleted
     * @throws EdgeControlException If gatewayDeviceId is null or blank
     */
    public boolean deleteAllForDevice(String gatewayDeviceId) throws EdgeControlException {
        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new EdgeControlException(
                    "Cannot delete queued requests: gatewayDeviceId is null or blank");
        }
        DeleteResult result = collection.deleteMany(
                Filters.eq("gatewayDeviceId", gatewayDeviceId));
        return result.getDeletedCount() > 0;
    }

    // | ================= Read operations ================= |

    /**
     * Returns all pending requests for a device, in insertion order.
     *
     * @param gatewayDeviceId Device to query
     * @return List of queued requests, empty if none exist
     */
    public List<QueuedRequest> findAllForDevice(String gatewayDeviceId) {
        List<QueuedRequest> result = new ArrayList<>();
        for (Document doc : collection.find(Filters.eq("gatewayDeviceId", gatewayDeviceId))) {
            result.add(new QueuedRequest(doc));
        }
        return result;
    }
}