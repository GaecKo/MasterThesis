package edge_control.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Provides a shared MongoClient for the edge-control application.
 * The client is created once and reused across all repository calls to avoid
 * connection pool exhaustion.
 */
public class MongoClientProvider {

    // TODO: move credentials to environment variables or a config file before production
    private static final String CONNECTION_STRING =
            "mongodb://root:example@mongodb:27017/?authSource=admin";

    // Single shared client — MongoClient is thread-safe and manages its own connection pool
    private static final MongoClient client = MongoClients.create(CONNECTION_STRING);

    // Utility class, not meant to be instantiated
    private MongoClientProvider() {}

    /**
     * Returns a MongoDatabase handle for the given database name.
     * The underlying MongoClient is shared across all calls.
     *
     * @param databaseName Name of the database to access
     * @return MongoDatabase handle (lightweight, safe to call repeatedly)
     */
    public static MongoDatabase getDatabase(String databaseName) {
        return client.getDatabase(databaseName);
    }
}