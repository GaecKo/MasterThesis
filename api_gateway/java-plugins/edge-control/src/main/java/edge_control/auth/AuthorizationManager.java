package edge_control.auth;

import edge_control.database.BackendAuthorizationsRepository;
import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceAuthorizationsRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles authorization checks and endpoint lookups for backends and devices.
 * Uses AuthRegistry as a first-level cache; falls back to the database on a miss
 * and populates the cache for subsequent requests.
 */
public class AuthorizationManager {

    private static AuthorizationManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendAuthorizationsRepository backendAuthorizations =
            new BackendAuthorizationsRepository();

    private final DeviceAuthorizationsRepository deviceAuthorizations =
            new DeviceAuthorizationsRepository();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();

    private final AuthRegistry authRegistry = AuthRegistry.getInstance();

    private AuthorizationManager() {
        logger.info("AuthorizationManager initialized");
    }

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized AuthorizationManager getInstance() {
        if (instance == null) {
            instance = new AuthorizationManager();
        }
        return instance;
    }

    // | ================= Authorization checks ================= |

    /**
     * Checks whether a backend is authorized to send a specific command to a specific device.
     * Consults the AuthRegistry cache first; fetches from DB and caches on a miss.
     *
     * @param gatewayId Gateway ID of the caller (must be backend_ prefixed)
     * @param body      Must contain 'gatewayDeviceId' and 'command'
     * @return True if the command is authorized, false otherwise
     */
    public boolean checkAuthorization(String gatewayId, Document body) {
        if (!gatewayId.startsWith("backend_")) return false;

        String deviceId = body.getString("gatewayDeviceId");
        String command  = body.getString("command");
        if (deviceId == null || command == null) return false;

        // Check cache first to avoid a DB round-trip on every request
        Document cached = authRegistry.getBackendAuth(gatewayId);
        if (cached != null) {
            logger.info("AuthRegistry cache hit for backend auth");
            List<String> commands = cached.getList(deviceId, String.class);
            return commands != null && commands.contains(command);
        }

        // Cache miss — fetch from DB, store in cache, then evaluate
        logger.info("AuthRegistry cache miss for backend auth");
        Document auths = backendAuthorizations.findAuthorizationsById(gatewayId);
        if (auths == null) return false;

        authRegistry.putBackendAuth(gatewayId, auths);
        List<String> commands = auths.getList(deviceId, String.class);
        return commands != null && commands.contains(command);
    }

    // | ================= Endpoint lookups ================= |

    /**
     * Returns a map of { gatewayBackendId → infoEndpoint } for all backends
     * a device is authorized to reach.
     * Consults the AuthRegistry cache first; falls back to DB on a miss.
     *
     * @param gatewayDeviceId Device whose authorized endpoints are requested
     * @return Map of backendId to endpoint (never null, may be empty)
     */
    public Map<String, String> getDeviceEndpoints(String gatewayDeviceId) {
        Map<String, String> cached = authRegistry.getDeviceEndpoints(gatewayDeviceId);
        if (cached != null) {
            logger.info("AuthRegistry cache hit for device endpoints");
            return cached;
        }

        // Cache miss — resolve each authorized backend's endpoint and cache the result
        logger.info("AuthRegistry cache miss for device endpoints");
        List<String> auths = deviceAuthorizations.findAuthorizationsById(gatewayDeviceId);
        Map<String, String> endpointsMap = new HashMap<>();
        if (auths != null) {
            for (String backendId : auths) {
                Document backendDoc = backendConfig.findBackendById(backendId);
                if (backendDoc != null) {
                    String endpoint = backendDoc.getString("infoEndpoint");
                    if (endpoint != null) endpointsMap.put(backendId, endpoint);
                }
            }
            authRegistry.putDeviceEndpoints(gatewayDeviceId, endpointsMap);
        }
        return endpointsMap;
    }

    /**
     * Returns the callback endpoint for a backend, used by the queuing layer for retry notifications.
     *
     * @param gatewayBackendId Backend to look up
     * @return Callback endpoint URL, or null if not configured
     */
    public String getCallbackEndpoint(String gatewayBackendId) {
        return authRegistry.getCallbackEndpoint(gatewayBackendId);
    }
}