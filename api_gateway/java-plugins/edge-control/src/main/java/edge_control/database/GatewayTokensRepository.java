package edge_control.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import edge_control.auth.tokens.GatewayTokensCrypto;
import edge_control.auth.tokens.TokenResponse;
import edge_control.exceptions.EdgeControlException;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for storing gateway outbound tokens by gatewayId.
 *
 * Tokens are encrypted at rest using AES-256-GCM before being stored in MongoDB.
 * The encryption key must be provided via the environment variable TOKEN_ENCRYPTION_KEY
 * as a Base64-encoded 32-byte (256-bit) value.
 *
 * Generate a key with:
 *   openssl rand -base64 32
 */
public class GatewayTokensRepository {

    private final MongoCollection<Document> collection;
    private final GatewayTokensCrypto crypto;

    /**
     * Connects to the "gatewayTokens" collection and loads the encryption key
     * from the TOKEN_ENCRYPTION_KEY environment variable.
     *
     * @throws EdgeControlException If the environment variable is missing or the key is invalid
     */
    public GatewayTokensRepository() throws EdgeControlException {
        MongoDatabase db = MongoClientProvider.getDatabase("edge_control");
        this.collection = db.getCollection("gatewayTokens");
        this.crypto = new GatewayTokensCrypto();
    }

    // | ================= Read operations ================= |

    /**
     * Finds the token document for a gateway and returns the decrypted token.
     *
     * @param gatewayId Gateway ID to look up (backend_ or device_ prefixed)
     * @return TokenResponse with type and decrypted token, or null if not found
     * @throws EdgeControlException If decryption fails
     */
    public TokenResponse findDecryptedTokenByGatewayId(String gatewayId) throws EdgeControlException {
        if (gatewayId == null || gatewayId.isBlank()) return null;

        Document doc = collection.find(Filters.eq("gatewayId", gatewayId)).first();
        if (doc == null) return null;

        String encryptedToken = doc.getString("token");
        if (encryptedToken == null) return null;

        String decryptedToken = crypto.decrypt(encryptedToken);
        String type = doc.getString("type");
        return new TokenResponse(type, decryptedToken);
    }

    /**
     * Raw find — returns the document with the encrypted token as stored.
     * Prefer findDecryptedTokenByGatewayId() in application code.
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
     * Encrypts and upserts a token for the given gateway ID.
     *
     * @param gatewayId Gateway ID (backend_ or device_ prefixed)
     * @param token     Plaintext token value to encrypt and store
     * @throws EdgeControlException If gatewayId or token is missing, or encryption fails
     */
    public void save(String gatewayId, String type, String token, String expiracyDate) throws EdgeControlException {
        if (gatewayId == null || gatewayId.isBlank()) {
            throw new EdgeControlException("gatewayId must be provided when saving a token");
        }
        if (token == null || token.isBlank()) {
            throw new EdgeControlException("token must be provided when saving a token");
        }

        String encryptedToken = crypto.encrypt(token);

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
     * @throws EdgeControlException If gatewayId is missing
     */
    public boolean delete(String gatewayId) throws EdgeControlException {
        if (gatewayId == null || gatewayId.isBlank()) {
            throw new EdgeControlException("gatewayId must be provided when deleting a token");
        }
        DeleteResult result = collection.deleteOne(Filters.eq("gatewayId", gatewayId));
        return result.getDeletedCount() > 0;
    }

    // | ================= Crypto operations ================= |
    // Crypto handled by GatewayTokensCrypto
}

