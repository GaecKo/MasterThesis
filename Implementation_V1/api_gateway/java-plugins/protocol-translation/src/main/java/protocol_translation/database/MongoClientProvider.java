package protocol_translation.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

public final class MongoClientProvider {

    private static final MongoClient client =
            MongoClients.create(
                    "mongodb://root:example@mongodb:27017/yourdb?authSource=admin"
            );


    private MongoClientProvider() {}

    public static MongoDatabase getDatabase() {
        return client.getDatabase("protocol_translation");
    }
}

