package edge_control.auth;

import edge_control.database.BackendAuthorizationsRepository;
import edge_control.database.DeviceAuthorizationsRepository;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.net.http.HttpRequest;

public class AuthorizationManager {

    private static AuthorizationManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendAuthorizationsRepository backendAuthorizations = new BackendAuthorizationsRepository();

    private final DeviceAuthorizationsRepository deviceAuthorizations = new DeviceAuthorizationsRepository();

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
        if (gatewayId.startsWith("backend_")){
            return backendAuthorizations.isAuthorized(gatewayId, body);
        } else if (gatewayId.startsWith("device_")){
            return deviceAuthorizations.isAuthorized(gatewayId, body);
        } else {
            return false;
        }

    }

}
