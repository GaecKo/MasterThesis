package edge_control.auth;

import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.exceptions.IllegalOperation;
import edge_control.logger.EdgeControlLogger;

/**
 * Validates API keys against the backend and device config repositories.
 * Uses AuthRegistry as a first-level cache keyed by API key hash to avoid
 * a DB round-trip on every request.
 */
public class AuthenticationManager {

    private static AuthenticationManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();
    private final DeviceConfigRepository  deviceConfig  = new DeviceConfigRepository();
    private final AuthRegistry            authRegistry  = AuthRegistry.getInstance();

    private AuthenticationManager() {
        logger.info("AuthenticationManager initialized");
    }

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized AuthenticationManager getInstance() {
        if (instance == null) {
            instance = new AuthenticationManager();
        }
        return instance;
    }

    // | ================= Authentication ================= |

    /**
     * Validates an API key and returns the associated gateway ID.
     * Checks the AuthRegistry cache first; falls back to the backend and device
     * repositories on a miss, caching the result for subsequent requests.
     *
     * TODO: validateApiKey() returns error strings instead of throwing — the string
     * comparisons below are fragile. Consider refactoring the repository layer to
     * throw or return null on invalid keys.
     *
     * @param apiKey Plain-text API key from the request header
     * @return Gateway ID (backend_ or device_ prefixed)
     * @throws IllegalOperation If the key is null or does not match any known identity
     */
    public String checkAuthentication(String apiKey) throws IllegalOperation {
        if (apiKey == null) {
            throw new IllegalOperation("API key cannot be null");
        }

        // Hash once and reuse for both the cache lookup and DB validation
        String hash = backendConfig.hashApiKey(apiKey);

        // Check cache first to avoid a DB round-trip on every request
        String cached = authRegistry.getGatewayId(hash);
        if (cached != null) {
            return cached;
        }

        // Cache miss — check backend repository first, then device repository
        String gatewayBackendId = backendConfig.validateApiKey(apiKey);
        if (!gatewayBackendId.startsWith("Invalid API key")
                && !gatewayBackendId.equals("API key cannot be null")) {
            authRegistry.putGatewayId(hash, gatewayBackendId);
            return gatewayBackendId;
        }

        String gatewayDeviceId = deviceConfig.validateApiKey(apiKey);
        if (!gatewayDeviceId.equals("Invalid API key")
                && !gatewayDeviceId.equals("API key cannot be null")) {
            authRegistry.putGatewayId(hash, gatewayDeviceId);
            return gatewayDeviceId;
        }

        throw new IllegalOperation("Invalid API key");
    }
}