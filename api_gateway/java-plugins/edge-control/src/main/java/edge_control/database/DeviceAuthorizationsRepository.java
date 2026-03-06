package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.List;

/**
 * Repository for creation and modification of a device entry from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "deviceAuthorizations" collection in MongoDB.
 * - Creates an entry in the collection for a device along with its authorization info.
 */
public class DeviceAuthorizationsRepository {

    private final MongoCollection<Document> deviceAuthorizationCollection;

    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public DeviceAuthorizationsRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.deviceAuthorizationCollection = db.getCollection("deviceAuthorizations");
    }

    /**
    * Adds device authorization details to the database.
    *
    * @param requestBody the request body containing device ID and authorization details
    * @return true if the operation was successful, false otherwise
    */
    public boolean addDeviceAuthorization(Document requestBody) {

        String deviceId = requestBody.getString("gatewayDeviceId");
        List<String> backendIds = requestBody.getList("listOfAuthorizations", String.class);

        if (deviceId == null || backendIds == null || backendIds.isEmpty()) {
            return false;
        }

        // Create a MongoDB document with the gatewayBackendId, hashed API key, and the provided config
        Document finalDoc = new Document();
        finalDoc.put("gatewayDeviceId", deviceId);
        finalDoc.put("listOfAuthorizations", backendIds);

        // Insert the document into MongoDB
        this.deviceAuthorizationCollection.insertOne(finalDoc);

        return true;
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
    public boolean updateDeviceAuthorization(Document requestBody) {
        String deviceId = requestBody.getString("gatewayDeviceId");
        List<String> authsToAdd = requestBody.getList("listOfAuthorizationsToAdd", String.class);
        List<String> authsToRemove = requestBody.getList("listOfAuthorizationsToRemove", String.class);

        // At least one of the lists must be present
        if (deviceId == null || (authsToAdd == null && authsToRemove == null)) {
            return false;
        }

        Document filter = new Document("gatewayDeviceId", deviceId);

        // Step 1: Remove authorizations first (if any)
        if (authsToRemove != null && !authsToRemove.isEmpty()) {
            Document updateDocRemove = new Document(
                "$pull",
                new Document("listOfAuthorizations", new Document("$in", authsToRemove))
            );
            deviceAuthorizationCollection.updateOne(filter, updateDocRemove);
        }

        // Step 2: Add authorizations after (if any)
        // Separated to avoid conflict when adding/removing from same field
        if (authsToAdd != null && !authsToAdd.isEmpty()) {
            Document updateDocAdd = new Document(
                "$addToSet",
                new Document("listOfAuthorizations", new Document("$each", authsToAdd))
            );
            deviceAuthorizationCollection.updateOne(filter, updateDocAdd);
        }

        return true;
    }

    /**
     * Deletes a device authorization entry from the database (DELETE operation).
     * This function removes the entire entry for a device, including all its authorization details.
     * @param requestBody containing the ID of the backend to delete
     * @return true if the operation was successful, false otherwise
     */
    public boolean deleteDeviceAuthorization(Document requestBody) {
        String deviceId = requestBody.getString("gatewayDeviceId");
        if (deviceId == null) {
            return false;
        }

        Document filter = new Document("gatewayDeviceId", deviceId);
        try {
            deviceAuthorizationCollection.deleteOne(filter);
            return true;
        } catch (Exception e) {
            return false;
        }

    }

    /**
     * Removes all gatewayBackendId from the list of authorizations for all the devices.
     * This is used when a backend is deleted to ensure that no device retains an authorization for a non-existent backend.
     *
     * @param requestBody the ID of the backend to remove from device authorizations
     * @return true if the operation was successful, false otherwise
     */
    public boolean removeBackendFromDeviceAuthorizations(Document requestBody) {
        String backendId = requestBody.getString("gatewayBackendId");

        if (backendId == null) {
            return false;
        }

        try {
            // Update all device authorization entries to remove the specified backend ID from their list of authorizations
            deviceAuthorizationCollection.updateMany(
                    new Document(), // Match all documents
                    new Document("$pull", new Document("listOfAuthorizations", backendId)) // Pull the backendId from the list
            );
            return true;
        } catch (Exception e) {
            return false;
        }

    }

    /**
     * Checks if the given gatewayDeviceId is authorized to perform the specified operation.
     *
     * @param gatewayDeviceId the ID of the backend to check authorization for
     * @param body the request body containing details of the info to send
     * @return true if authorized, false otherwise
     */
    public boolean isAuthorized(String gatewayDeviceId, Document body) {
        Document deviceAuth = deviceAuthorizationCollection.find(
                new Document("gatewayDeviceId", gatewayDeviceId)).first();

        String gatewayBackendId = body.getString("gatewayBackendId");

        if (deviceAuth == null || gatewayBackendId == null) {
            return false;
        }

        List<String> listOfAuthorizations = deviceAuth.getList("listOfAuthorizations", String.class);
        if (listOfAuthorizations == null) {
            return false;
        }

        return listOfAuthorizations.contains(gatewayBackendId);

    }

}
