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
        List<String> backendIds = (List<String>) requestBody.get("listOfAuthorizations");

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

}
