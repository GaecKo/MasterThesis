package edge_control.auth;

import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.logger.EdgeControlLogger;

public class AuthenticationManager {

    private static AuthenticationManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();

    private final DeviceConfigRepository deviceConfig = new DeviceConfigRepository();



    private AuthenticationManager() {
        // Private constructor to prevent instantiation
        logger.info("AuthenticationManager initialized");
    }

    public static AuthenticationManager getInstance() {
        if (instance == null) {
            instance = new AuthenticationManager();
        }
        return instance;
    }

    public String checkAuthentication(String apiKey){
        String gatewayBackendId = backendConfig.validateApiKey(apiKey);
        if (!gatewayBackendId.startsWith("Invalid API key") && !gatewayBackendId.equals("API key cannot be null")) {
            return gatewayBackendId;
        } else {
            String gatewayDeviceId = deviceConfig.validateApiKey(apiKey);
            if (!gatewayDeviceId.equals("Invalid API key") && !gatewayDeviceId.equals("API key cannot be null")) {
                return gatewayDeviceId;
            } else {
                return "Invalid API key or API key cannot be null";
            }
        }
    }

}
