package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import edge_control.exceptions.EdgeControlException;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for storing gateway outbound tokens by gatewayId.
 *
 * Tokens are stored in encrypted form; encryption/decryption is handled outside.
 */
public class GatewayTokensRepository {

    private final MongoCollection<Document> collection;

    /**
     * Connects to the "gatewayTokens" collection.
     */
    public GatewayTokensRepository() throws EdgeControlException {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.collection = db.getCollection("gatewayTokens");
    }

    // | ================= Read operations ================= |

    /**
     * Raw find — returns the document with the encrypted token as stored.
     *
     * @param gatewayId Gateway ID to look up
     * @return Raw document, or null if not found
     */
    public Document findByGatewayId(String gatewayId) {
        if (gatewayId == null || gatewayId.isBlank()) return null;
        return collection.find(Filters.eq("gatewayId", gatewayId)).first();
    }

    /**
     * @return All token documents in the collection (tokens are encrypted)
     */
    public List<Document> findAll() {
        return collection.find().into(new ArrayList<>());
    }

    // | ================= Write operations ================= |

    /**
     * Stores an already-encrypted token for the given gateway ID.
     *
     * @param gatewayId       Gateway ID (backend_ or device_ prefixed)
     * @param encryptedToken  Encrypted token value to store
     * @param expiracyDate    Expiry date metadata (optional)
     * @throws EdgeControlException If gatewayId or encryptedToken is missing
     */
    public void save(String gatewayId, String type, String encryptedToken, String expiracyDate)
            throws EdgeControlException {
        if (gatewayId == null || gatewayId.isBlank()) {
            throw new EdgeControlException("gatewayId must be provided when saving a token");
        }
        if (encryptedToken == null || encryptedToken.isBlank()) {
            throw new EdgeControlException("token must be provided when saving a token");
        }

        Document doc = new Document();
        doc.put("gatewayId", gatewayId);
        doc.put("type", type);
        doc.put("token", encryptedToken);
        doc.put("expiracyDate", expiracyDate);

        collection.replaceOne(
                Filters.eq("gatewayId", gatewayId),
                doc,
                new ReplaceOptions().upsert(true));
    }

    /**
     * Deletes a token entry for a gateway.
     *
     * @param gatewayId Gateway ID to delete
     * @return True if a document was found and deleted, false otherwise
     */
    public boolean delete(String gatewayId) {
        if (gatewayId == null || gatewayId.isBlank()) {
            return false;
        }
        DeleteResult result = collection.deleteOne(Filters.eq("gatewayId", gatewayId));
        return result.getDeletedCount() > 0;
    }
}
