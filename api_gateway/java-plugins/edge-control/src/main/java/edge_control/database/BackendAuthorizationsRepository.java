package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for managing backend authorization entries in MongoDB.
 * Each entry stores the list of commands a backend is authorized to send to each device.
 */
public class BackendAuthorizationsRepository {

    private final MongoCollection<Document> backendAuthorizationCollection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Connects to the "backendAuthorizations" collection in the edge_control database.
     */
    public BackendAuthorizationsRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.backendAuthorizationCollection = db.getCollection("backendAuthorizations");
    }

    // | ================= Write operations ================= |

    /**
     * Inserts a new backend authorization entry.
     * Returns false if the entry already exists (use updateBackendAuthorization instead).
     *
     * @param requestBody Must contain 'gatewayBackendId' and 'listOfAuthorizations'
     * @return True if inserted, false if the entry already exists or required fields are missing
     */
    public boolean addBackendAuthorization(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");
        Document newAuths = (Document) requestBody.get("listOfAuthorizations");

        if (backendId == null || newAuths == null) {
            return false;
        }

        // Reject if an entry already exists — callers should use update instead
        Document existingEntry = backendAuthorizationCollection.find(
                new Document("gatewayBackendId", backendId)).first();
        if (existingEntry != null) {
            return false;
        }

        Document newDocument = new Document();
        newDocument.put("gatewayBackendId", backendId);
        newDocument.put("listOfAuthorizations", newAuths);

        try {
            backendAuthorizationCollection.insertOne(newDocument);
            return true;
        } catch (Exception e) {
            logger.error("Failed to insert backend authorization for " + backendId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Adds or removes authorized commands for an existing backend entry.
     *
     * Remove is applied before add to avoid MongoDB conflicts when the same
     * field appears in both lists.
     *
     * @param requestBody Must contain 'gatewayBackendId' and at least one of
     *                    'listOfAuthorizationsToAdd' or 'listOfAuthorizationsToRemove'
     * @return True if at least one update matched a document, false otherwise
     */
    public boolean updateBackendAuthorization(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");
        Document authsToAdd    = (Document) requestBody.get("listOfAuthorizationsToAdd");
        Document authsToRemove = (Document) requestBody.get("listOfAuthorizationsToRemove");

        if (backendId == null || (authsToAdd == null && authsToRemove == null)) {
            return false;
        }

        Document filter = new Document("gatewayBackendId", backendId);

        // Step 1: Remove authorizations first to avoid conflicts with step 2
        UpdateResult removeResult = null;
        if (authsToRemove != null && !authsToRemove.isEmpty()) {
            Document pullDoc = new Document();
            for (String deviceId : authsToRemove.keySet()) {
                Object commandsObj = authsToRemove.get(deviceId);
                if (commandsObj instanceof List<?> commands) {
                    // $pull removes each listed command from the array for this device
                    pullDoc.put("listOfAuthorizations." + deviceId,
                            new Document("$in", commands));
                }
            }
            if (!pullDoc.isEmpty()) {
                removeResult = backendAuthorizationCollection.updateOne(
                        filter, new Document("$pull", pullDoc));
            }
        }

        // Step 2: Add new authorizations using $addToSet to avoid duplicates
        UpdateResult addResult = null;
        if (authsToAdd != null && !authsToAdd.isEmpty()) {
            Document addToSetDoc = new Document();
            for (String deviceId : authsToAdd.keySet()) {
                Object commandsObj = authsToAdd.get(deviceId);
                if (commandsObj instanceof List<?> commands) {
                    // $each with $addToSet adds multiple items without duplicating existing ones
                    addToSetDoc.put("listOfAuthorizations." + deviceId,
                            new Document("$each", commands));
                }
            }
            if (!addToSetDoc.isEmpty()) {
                addResult = backendAuthorizationCollection.updateOne(
                        filter, new Document("$addToSet", addToSetDoc));
            }
        }

        // Return false if any attempted update found no matching document
        if ((removeResult != null && removeResult.getMatchedCount() == 0) ||
                (addResult    != null && addResult.getMatchedCount()    == 0)) {
            return false;
        }

        return true;
    }

    /**
     * Deletes the entire authorization entry for a backend.
     *
     * @param requestBody Must contain 'gatewayBackendId'
     * @return True if the delete succeeded, false if the ID is missing
     */
    public boolean deleteBackendAuthorization(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");
        if (backendId == null) {
            return false;
        }

        try {
            backendAuthorizationCollection.deleteOne(
                    new Document("gatewayBackendId", backendId));
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete backend authorization for " + backendId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes a device's authorization entry from all backends.
     * Called when a device is deleted to prevent dangling references.
     * The entire field key is removed via $unset, not just emptied.
     *
     * @param requestBody Must contain 'gatewayDeviceId'
     * @return True if the update succeeded, false if the ID is missing
     */
    public boolean removeDeviceFromBackendAuthorizations(Document requestBody) {
        String deviceId = requestBody.getString("gatewayDeviceId");
        if (deviceId == null) {
            return false;
        }

        // Find all backends that have an entry for this device
        Document filter = new Document("listOfAuthorizations." + deviceId,
                new Document("$exists", true));

        // $unset removes the field entirely rather than setting it to null or empty
        Document updateDoc = new Document("$unset",
                new Document("listOfAuthorizations." + deviceId, ""));

        try {
            backendAuthorizationCollection.updateMany(filter, updateDoc);
            return true;
        } catch (Exception e) {
            logger.error("Failed to remove device " + deviceId + " from backend authorizations: " + e.getMessage());
            return false;
        }
    }

    // | ================= Read operations ================= |

    /**
     * Checks whether a backend is authorized to send a specific command to a specific device.
     *
     * @param gatewayBackendId Backend to check authorization for
     * @param body             Must contain 'gatewayDeviceId' and 'command'
     * @return True if the command is in the backend's authorization list for that device
     */
    public boolean isAuthorized(String gatewayBackendId, Document body) {
        Document backendAuth = backendAuthorizationCollection.find(
                new Document("gatewayBackendId", gatewayBackendId)).first();

        String gatewayDeviceId = body.getString("gatewayDeviceId");
        String commandName     = body.getString("command");

        if (backendAuth == null || gatewayDeviceId == null || commandName == null) {
            return false;
        }

        Document listOfAuthorizations = (Document) backendAuth.get("listOfAuthorizations");
        if (listOfAuthorizations == null) {
            return false;
        }

        // Look up the list of authorized commands for this specific device
        List<String> authorizedCommands = listOfAuthorizations.getList(gatewayDeviceId, String.class);
        if (authorizedCommands == null) {
            return false;
        }

        return authorizedCommands.contains(commandName);
    }

    /**
     * @return All backend authorization documents in the collection
     */
    public List<Document> findAll() {
        return backendAuthorizationCollection.find().into(new ArrayList<>());
    }

    /**
     * @param gatewayBackendId Backend to look up
     * @return The listOfAuthorizations document for the given backend, or null if not found
     */
    public Document findAuthorizationsById(String gatewayBackendId) {
        Document doc = backendAuthorizationCollection.find(
                new Document("gatewayBackendId", gatewayBackendId)).first();
        return doc != null ? (Document) doc.get("listOfAuthorizations") : null;
    }
}