package edge_control.auth.tokens;

import edge_control.database.GatewayTokensRepository;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles storage of outbound tokens for gateways (backend or device).
 */
public class GatewayTokensRegistry {

    private static GatewayTokensRegistry instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final GatewayTokensRepository repository = new GatewayTokensRepository();

    private final Map<String, TokenResponse> tokenCache = new ConcurrentHashMap<>();

    private GatewayTokensRegistry() throws EdgeControlException {
        logger.info("GatewayTokensManager initialized");
    }

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized GatewayTokensRegistry getInstance() throws EdgeControlException {
        if (instance == null) {
            instance = new GatewayTokensRegistry();
        }
        return instance;
    }

    /**
     * Stores or updates a token for the provided gatewayId.
     *
     * @param securityObject JSON string containing 'gatewayId' and 'token'
     * @return Document with 'status' and 'message'
     */
    public Document upsertToken(String gatewayId, JSONObject securityObject) {
        Document responseDoc = new Document();

        String token = securityObject.optString("token", null);
        String type = securityObject.optString("type", null);
        String expiracyDate = securityObject.optString("expiracyDate", "N/A");

        if (gatewayId == null || gatewayId.isBlank()) {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "gatewayId is required");
            return responseDoc;
        }
        if (token == null || token.isBlank()) {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "security.token is required");
            return responseDoc;
        }
        if (type == null || type.isBlank()) {
            responseDoc.put("status", "failure");
            responseDoc.put("message", "security.type is required");
            return responseDoc;
        }

        try {
            repository.save(gatewayId, type, token, expiracyDate);
            tokenCache.put(gatewayId, new TokenResponse(type, token));
            responseDoc.put("status", "success");
            responseDoc.put("message", "Token stored successfully.");
            responseDoc.put("gatewayId", gatewayId);
        } catch (EdgeControlException e) {
            responseDoc.put("status", "failure");
            responseDoc.put("message", e.getMessage());
        }

        return responseDoc;
    }
    /**
     * Retrieves the decrypted token for a gateway.
     *
     * @param gatewayId Gateway ID (backend_ or device_ prefixed)
     * @return Decrypted token, or null if not found
     * @throws EdgeControlException If decryption fails or key is missing
     */
    public TokenResponse getDecryptedTokenByGatewayId(String gatewayId) throws EdgeControlException {
        TokenResponse cached = tokenCache.get(gatewayId);
        if (cached != null) return cached;

        TokenResponse response = repository.findDecryptedTokenByGatewayId(gatewayId);
        if (response != null) {
            tokenCache.put(gatewayId, response);
            return response;
        }
        return null;
    }
}
