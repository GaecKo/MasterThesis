package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for creation and modification of a backend entry from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "backendAuthorizations" collection in MongoDB.
 * - Creates an entry in the collection for a backend along with its authorization info.
 */
public class BackendAuthorizationsRepository {

    private final MongoCollection<Document> backendAuthorizationCollection;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public BackendAuthorizationsRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.backendAuthorizationCollection = db.getCollection("backendAuthorizations");
    }

    /**
     * Creates a new backend authorization entry in the database (POST operation).
     * This function inserts a fresh entry with initial authorization details.
     *
     * @param requestBody the request body containing backend ID and authorization details
     * @return true if the operation was successful, false otherwise
     */
    public boolean addBackendAuthorization(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");
        Document newAuths = (Document) requestBody.get("listOfAuthorizations");

        if (backendId == null || newAuths == null) {
            return false;
        }

        // Check if backend authorization entry already exists
        Document existingEntry = backendAuthorizationCollection.find(
                new Document("gatewayBackendId", backendId)
        ).first();

        if (existingEntry != null) {
            // Entry already exists, use update instead
            return false;
        }

        // Create new document with initial authorizations
        Document newDocument = new Document();
        newDocument.put("gatewayBackendId", backendId);
        newDocument.put("listOfAuthorizations", newAuths);

        try {
            backendAuthorizationCollection.insertOne(newDocument);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Updates an existing backend authorization entry in the database (PATCH operation).
     * This function adds or removes authorization details for an existing backend.
     *
     * Note: If the same field is being both added to and removed from, we split this into
     * two separate operations to avoid MongoDB conflicts.
     *
     * @param requestBody the request body containing backend ID and authorization details to add/remove
     * @return true if the operation was successful, false otherwise
     */
    public boolean updateBackendAuthorization(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");
        Document authsToAdd = (Document) requestBody.get("listOfAuthorizationsToAdd");
        Document authsToRemove = (Document) requestBody.get("listOfAuthorizationsToRemove");

        // At least one of the lists must be present
        if (backendId == null || (authsToAdd == null && authsToRemove == null)) {
            return false;
        }

        Document filter = new Document("gatewayBackendId", backendId);

        // Step 1: Remove authorizations first (if any)
        UpdateResult updateDocRemoveResult = null;

        if (authsToRemove != null && !authsToRemove.isEmpty()) {
            Document pullDoc = new Document();

            for (String deviceId : authsToRemove.keySet()) {
                Object commandsObj = authsToRemove.get(deviceId);

                if (commandsObj instanceof List<?>) {
                    List<?> commands = (List<?>) commandsObj;

                    pullDoc.put(
                            "listOfAuthorizations." + deviceId,
                            new Document("$in", commands)
                    );
                }
            }

            if (!pullDoc.isEmpty()) {
                Document updateDocRemove = new Document("$pull", pullDoc);
                updateDocRemoveResult = backendAuthorizationCollection.updateOne(filter, updateDocRemove);
            }
        }

        // Step 2: Add authorizations after (if any)
        // Separated to avoid conflict when adding/removing from same field
        UpdateResult updateDocAddResult = null;

        if (authsToAdd != null && !authsToAdd.isEmpty()) {
            Document addToSetDoc = new Document();

            for (String deviceId : authsToAdd.keySet()) {
                Object commandsObj = authsToAdd.get(deviceId);

                if (commandsObj instanceof List<?>) {
                    List<?> commands = (List<?>) commandsObj;

                    addToSetDoc.put(
                            "listOfAuthorizations." + deviceId,
                            new Document("$each", commands)
                    );
                }
            }

            if (!addToSetDoc.isEmpty()) {
                Document updateDocAdd = new Document("$addToSet", addToSetDoc);
                updateDocAddResult = backendAuthorizationCollection.updateOne(filter, updateDocAdd);
            }
        }

        if ((updateDocRemoveResult != null && updateDocRemoveResult.getMatchedCount() == 0) ||
                (updateDocAddResult != null && updateDocAddResult.getMatchedCount() == 0)) {
            return false; // No matching document found for update
        }

        return true;
    }

    /**
     * Deletes a backend authorization entry from the database (DELETE operation).
     * This function removes the entire entry for a backend, including all its authorization details.
     * @param requestBody containing the ID of the backend to delete
     * @return true if the operation was successful, false otherwise
     */
    public boolean deleteBackendAuthorization(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");
        if (backendId == null) {
            return false;
        }

        Document filter = new Document("gatewayBackendId", backendId);
        try {
            backendAuthorizationCollection.deleteOne(filter);
            return true;
        } catch (Exception e) {
            return false;
        }

    }

    /**
     * Removes all gatewayDeviceId from the list of authorizations for all the backends.
     * This is used when a device is deleted to ensure that no device retains an authorization for a non-existent device.
     * The entire field (deviceId key) is removed from listOfAuthorizations, not just emptied.
     *
     * @param requestBody the ID of the device to remove from backend authorizations
     * @return true if the operation was successful, false otherwise
     */
    public boolean removeDeviceFromBackendAuthorizations(Document requestBody) {
        String deviceId = requestBody.getString("gatewayDeviceId");

        if (deviceId == null) {
            return false;
        }

        // Filter: find all documents that have this deviceId in listOfAuthorizations
        Document filter = new Document("listOfAuthorizations." + deviceId, new Document("$exists", true));

        // Update: use $unset to completely remove the field
        Document unsetDoc = new Document("listOfAuthorizations." + deviceId, "");
        Document updateDoc = new Document("$unset", unsetDoc);

        try {
            backendAuthorizationCollection.updateMany(filter, updateDoc);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if the given gatewayBackendId is authorized to perform the specified operation.
     *
     * @param gatewayBackendId the ID of the backend to check authorization for
     * @param body the request body containing details of the command to perform
     * @return true if authorized, false otherwise
     */
    public boolean isAuthorized(String gatewayBackendId, Document body) {
        Document backendAuth = backendAuthorizationCollection.find(
                new Document("gatewayBackendId", gatewayBackendId)).first();

        String gatewayDeviceId = body.getString("gatewayDeviceId");
        String commandName = body.getString("command");

        if (backendAuth == null || gatewayDeviceId == null || commandName == null) {
            return false;
        }

        Document listOfAuthorizations = (Document) backendAuth.get("listOfAuthorizations");
        if (listOfAuthorizations == null) {
            return false;
        }

        List<String> authorizedCommandsObj = listOfAuthorizations.getList(gatewayDeviceId, String.class);
        if (authorizedCommandsObj == null) {
            return false;
        }

        return authorizedCommandsObj.contains(commandName);

    }

    public List<Document> findAll() {
        return backendAuthorizationCollection.find().into(new ArrayList<>());
    }
    public Document findAuthorizationsById(String gatewayBackendId) {
        Document doc = backendAuthorizationCollection.find(
                new Document("gatewayBackendId", gatewayBackendId)).first();
        return doc != null ? (Document) doc.get("listOfAuthorizations") : null;
    }

}
