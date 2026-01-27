package protocol_translation.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import protocol_translation.device.config.DeviceConfig;

public class DeviceConfigRepository {

    private final MongoCollection<Document> collection;

    public DeviceConfigRepository() {
        MongoDatabase db = MongoClientProvider.getDatabase();
        this.collection = db.getCollection("devices");
    }

    public List<DeviceConfig> findAll() {
        List<DeviceConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            result.add(fromDocument(doc));
        }
        return result;
    }

    private DeviceConfig fromDocument(Document doc) {
        return new DeviceConfig(
                doc.getString("deviceId"),
                doc.getString("adapter"),
                new JSONObject(doc.toJson())
        );
    }

    public void save(DeviceConfig config) {
        Document doc = new Document(config.getConfig().toMap());
        collection.replaceOne(
                Filters.eq("deviceId", config.getDeviceId()),
                doc,
                new ReplaceOptions().upsert(true)
        );
    }


}

