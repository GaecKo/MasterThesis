package edge_control.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Provides access to collection in database using MongoClient
 * for the edge-control application.
 *
 * Responsibilities:
 * - Provides access to a collection within a database.
 */
public class MongoClientProvider {

    /**
     * Returns the MongoDatabase instance used by the application.
     *
     * @return the "databaseName" MongoDatabase
     */
    public static MongoDatabase getDatabase(String databaseName) {
        MongoClient client = MongoClients.create(
                "mongodb://root:example@mongodb:27017/?authSource=admin"
        );
        return client.getDatabase(databaseName);
    }
}
