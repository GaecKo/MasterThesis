package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Repository for creation and modification of a backend entry from MongoDB.
 *
 * Responsibilities:
 * - Provides access to the "backendAuthorizations" collection in MongoDB.
 * - Creates an entry in the collection for a backend along with its authorization info.
 */
public class BackendAuthorizationsRepository {

    private final MongoCollection<Document> backendAuthorizationCollection;

    /**
     * Initializes the repository by connecting to the "devices" collection
     * in the configured MongoDB database.
     */
    public BackendAuthorizationsRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.backendAuthorizationCollection = db.getCollection("backendAuthorizations");
    }

    /**
    * Adds backend authorization details to the database.
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

        // Build dynamic update for each device
        Document addToSetDoc = new Document();

        for (String deviceId : newAuths.keySet()) {
            Object commandsObj = newAuths.get(deviceId);

            if (commandsObj instanceof List<?>) {
                List<?> commands = (List<?>) commandsObj;

                addToSetDoc.put(
                        "listOfAuthorizations." + deviceId,
                        new Document("$each", commands)
                );
            }
        }

        Document update = new Document("$addToSet", addToSetDoc);

        // Upsert = insert if not exists
        backendAuthorizationCollection.updateOne(
                new Document("gatewayBackendId", backendId),
                update,
                new com.mongodb.client.model.UpdateOptions().upsert(true)
        );

        return true;
    }

}
