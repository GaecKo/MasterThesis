package edge_control.auth;

import edge_control.database.BackendAuthorizationsRepository;
import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceAuthorizationsRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AuthorizationManager {

    private static AuthorizationManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendAuthorizationsRepository backendAuthorizations = new BackendAuthorizationsRepository();

    private final DeviceAuthorizationsRepository deviceAuthorizations = new DeviceAuthorizationsRepository();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();

    private final AuthRegistry authRegistry = AuthRegistry.getInstance();

    private AuthorizationManager() {
        // Private constructor to prevent instantiation
        logger.info("AuthorizationManager initialized");
    }

    public static AuthorizationManager getInstance() {
        if (instance == null) {
            instance = new AuthorizationManager();
        }
        return instance;
    }

    /**
     * Checks if the given gatewayBackendId is authorized to perform the specified operation.
     *
     * @param gatewayId the ID of the backend to check authorization for
     * @param body the request body containing details of the command to perform
     * @return true if authorized, false otherwise
     */
    public boolean checkAuthorization(String gatewayId, Document body) {
        if (gatewayId.startsWith("backend_")) {

            // 1. Check registry cache first
            Document cached = authRegistry.getBackendAuth(gatewayId);
            if (cached != null) {
                logger.info("AuthRegistry cache hit for backend auth");
                String deviceId = body.getString("gatewayDeviceId");
                String command  = body.getString("command");
                if (deviceId == null || command == null) return false;
                List<String> commands = cached.getList(deviceId, String.class);
                return commands != null && commands.contains(command);
            }

            // 2. Cache miss — fetch once, cache, then evaluate
            logger.info("AuthRegistry cache miss for backend auth");
            Document auths = backendAuthorizations.findAuthorizationsById(gatewayId);
            if (auths != null) {
                authRegistry.putBackendAuth(gatewayId, auths);
                String deviceId = body.getString("gatewayDeviceId");
                String command  = body.getString("command");
                if (deviceId == null || command == null) return false;
                List<String> commands = auths.getList(deviceId, String.class);
                return commands != null && commands.contains(command);
            }
            return false;

        }

        return false;
    }

    /**
     * Returns a map of { gatewayBackendId → endpoint } for all backends a device is authorized to reach.
     * Checks the AuthRegistry cache first; falls back to DB on a cache miss and caches the result.
     *
     * @param gatewayDeviceId the device whose authorized endpoints are requested
     * @return map of backendId → endpoint (never null, may be empty)
     */
    public Map<String, String> getDeviceEndpoints(String gatewayDeviceId) {
        // 1. Check registry cache first
        Map<String, String> cached = authRegistry.getDeviceEndpoints(gatewayDeviceId);
        if (cached != null) {
            logger.info("AuthRegistry cache hit for device endpoints");
            return cached;
        }

        // 2. Cache miss — fetch authorized backends, resolve endpoints, cache and return
        logger.info("AuthRegistry cache miss for device endpoints");
        List<String> auths = deviceAuthorizations.findAuthorizationsById(gatewayDeviceId);
        Map<String, String> endpointsMap = new HashMap<>();
        if (auths != null) {
            for (String backendId : auths) {
                Document backendDoc = backendConfig.findBackendById(backendId);
                if (backendDoc != null) {
                    String endpoint = backendDoc.getString("infoEndpoint");
                    if (endpoint != null) {
                        endpointsMap.put(backendId, endpoint);
                    }
                }
            }
            authRegistry.putDeviceEndpoints(gatewayDeviceId, endpointsMap);
        }
        return endpointsMap;
    }

}
