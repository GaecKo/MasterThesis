package edge_control.auth;

import edge_control.database.BackendAuthorizationsRepository;
import edge_control.database.DeviceAuthorizationsRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.List;

public class AuthorizationManager {

    private static AuthorizationManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendAuthorizationsRepository backendAuthorizations = new BackendAuthorizationsRepository();

    private final DeviceAuthorizationsRepository deviceAuthorizations = new DeviceAuthorizationsRepository();

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

        } else if (gatewayId.startsWith("device_")) {

            // 1. Check registry cache first
            List<String> cached = authRegistry.getDeviceAuth(gatewayId);
            if (cached != null) {
                logger.info("AuthRegistry cache hit for device auth");
                String backendId = body.getString("gatewayBackendId");
                return backendId != null && cached.contains(backendId);
            }

            // 2. Cache miss — fetch once, cache, then evaluate
            logger.info("AuthRegistry cache miss for device auth");
            List<String> auths = deviceAuthorizations.findAuthorizationsById(gatewayId);
            if (auths != null) {
                authRegistry.putDeviceAuth(gatewayId, auths);
                String backendId = body.getString("gatewayBackendId");
                return backendId != null && auths.contains(backendId);
            }
            return false;
        }

        return false;
    }

}
