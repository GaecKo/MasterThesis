package edge_control.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Provides a singleton MongoClient and access to the MongoDB database
 * for the protocol_translation application.
 *
 * Responsibilities:
 * - Maintains a single MongoClient instance for the application.
 * - Provides access to the "protocol_translation" database.
 * - Ensures centralized MongoDB connection configuration.
 */
public final class MongoClientProvider {

    /**
     * Singleton MongoClient connecting to MongoDB with authentication.
     */
    private static final MongoClient client =
            MongoClients.create(
                    "mongodb://root:example@mongodb:27017/devices?authSource=admin"
            );

    /**
     * Private constructor to prevent instantiation.
     */
    private MongoClientProvider() {}

    /**
     * Returns the MongoDatabase instance used by the application.
     *
     * @return the "protocol_translation" MongoDatabase
     */
    public static MongoDatabase getDatabase() {
        return client.getDatabase("protocol_translation");
    }
}
