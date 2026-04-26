package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for managing device authorization entries in MongoDB.
 * Each entry stores the list of backend IDs that are authorized to send commands to a device.
 */
public class DeviceAuthorizationsRepository {

    private final MongoCollection<Document> deviceAuthorizationCollection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Connects to the "deviceAuthorizations" collection in the edge_control database.
     */
    public DeviceAuthorizationsRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.deviceAuthorizationCollection = db.getCollection("deviceAuthorizations");
    }

    // | ================= Write operations ================= |

    /**
     * Inserts a new device authorization entry.
     * Returns false if the entry already exists (use updateDeviceAuthorization instead).
     *
     * @param requestBody Must contain 'gatewayDeviceId' and 'listOfAuthorizations' (non-empty)
     * @return True if inserted, false if the entry already exists or required fields are missing
     */
    public boolean addDeviceAuthorization(Document requestBody) {
        String deviceId = requestBody.getString("gatewayDeviceId");
        List<String> backendIds = requestBody.getList("listOfAuthorizations", String.class);

        if (deviceId == null || backendIds == null || backendIds.isEmpty()) {
            return false;
        }

        // Reject if an entry already exists — callers should use update instead
        Document existingEntry = deviceAuthorizationCollection.find(
                new Document("gatewayDeviceId", deviceId)).first();
        if (existingEntry != null) {
            return false;
        }

        Document finalDoc = new Document();
        finalDoc.put("gatewayDeviceId", deviceId);
        finalDoc.put("listOfAuthorizations", backendIds);

        deviceAuthorizationCollection.insertOne(finalDoc);
        return true;
    }

    /**
     * Adds or removes authorized backends for an existing device entry.
     * Remove is applied before add to avoid MongoDB conflicts when the same
     * backend ID appears in both lists.
     *
     * @param requestBody Must contain 'gatewayDeviceId' and at least one of
     *                    'listOfAuthorizationsToAdd' or 'listOfAuthorizationsToRemove'
     * @return True if at least one update matched a document, false otherwise
     */
    public boolean updateDeviceAuthorization(Document requestBody) {
        String deviceId = requestBody.getString("gatewayDeviceId");
        List<String> authsToAdd    = requestBody.getList("listOfAuthorizationsToAdd",    String.class);
        List<String> authsToRemove = requestBody.getList("listOfAuthorizationsToRemove", String.class);

        if (deviceId == null || (authsToAdd == null && authsToRemove == null)) {
            return false;
        }

        Document filter = new Document("gatewayDeviceId", deviceId);

        // Step 1: Remove backends first to avoid conflicts with step 2
        UpdateResult removeResult = null;
        if (authsToRemove != null && !authsToRemove.isEmpty()) {
            removeResult = deviceAuthorizationCollection.updateOne(filter,
                    new Document("$pull",
                            new Document("listOfAuthorizations",
                                    new Document("$in", authsToRemove))));
        }

        // Step 2: Add backends using $addToSet to avoid duplicates
        UpdateResult addResult = null;
        if (authsToAdd != null && !authsToAdd.isEmpty()) {
            addResult = deviceAuthorizationCollection.updateOne(filter,
                    new Document("$addToSet",
                            new Document("listOfAuthorizations",
                                    new Document("$each", authsToAdd))));
        }

        // Return false if any attempted update found no matching document
        if ((removeResult != null && removeResult.getMatchedCount() == 0) ||
                (addResult    != null && addResult.getMatchedCount()    == 0)) {
            return false;
        }

        return true;
    }

    /**
     * Deletes the entire authorization entry for a device.
     *
     * @param requestBody Must contain 'gatewayDeviceId'
     * @return True if the delete succeeded, false if the ID is missing
     */
    public boolean deleteDeviceAuthorization(Document requestBody) {
        String deviceId = requestBody.getString("gatewayDeviceId");
        if (deviceId == null) return false;

        try {
            deviceAuthorizationCollection.deleteOne(
                    new Document("gatewayDeviceId", deviceId));
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete device authorization for " + deviceId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes a backend from the authorization list of all devices.
     * Called when a backend is deleted to prevent dangling references.
     *
     * @param requestBody Must contain 'gatewayBackendId'
     * @return True if the update succeeded, false if the ID is missing
     */
    public boolean removeBackendFromDeviceAuthorizations(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");
        if (backendId == null) return false;

        try {
            // Match all device documents and pull this backend from each authorization list
            deviceAuthorizationCollection.updateMany(
                    new Document(),
                    new Document("$pull",
                            new Document("listOfAuthorizations", backendId)));
            return true;
        } catch (Exception e) {
            logger.error("Failed to remove backend " + backendId + " from device authorizations: " + e.getMessage());
            return false;
        }
    }

    // | ================= Read operations ================= |

    /**
     * Checks whether a device is authorized to receive commands from the given backend.
     *
     * @param gatewayDeviceId Device to check authorization for
     * @param body            Must contain 'gatewayBackendId'
     * @return True if the backend is in the device's authorization list
     */
    public boolean isAuthorized(String gatewayDeviceId, Document body) {
        Document deviceAuth = deviceAuthorizationCollection.find(
                new Document("gatewayDeviceId", gatewayDeviceId)).first();

        String gatewayBackendId = body.getString("gatewayBackendId");

        if (deviceAuth == null || gatewayBackendId == null) {
            return false;
        }

        List<String> listOfAuthorizations =
                deviceAuth.getList("listOfAuthorizations", String.class);
        if (listOfAuthorizations == null) {
            return false;
        }

        return listOfAuthorizations.contains(gatewayBackendId);
    }

    /**
     * @return All device authorization documents in the collection
     */
    public List<Document> findAll() {
        return deviceAuthorizationCollection.find().into(new ArrayList<>());
    }

    /**
     * @param gatewayDeviceId Device to look up
     * @return List of authorized backend IDs for the device, or null if not found
     */
    public List<String> findAuthorizationsById(String gatewayDeviceId) {
        Document doc = deviceAuthorizationCollection.find(
                new Document("gatewayDeviceId", gatewayDeviceId)).first();
        return doc != null ? doc.getList("listOfAuthorizations", String.class) : null;
    }
}