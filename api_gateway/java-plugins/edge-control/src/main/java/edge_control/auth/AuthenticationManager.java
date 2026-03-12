package edge_control.auth;

import edge_control.database.BackendConfigRepository;
import edge_control.database.DeviceConfigRepository;
import edge_control.logger.EdgeControlLogger;

public class AuthenticationManager {

    private static AuthenticationManager instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();

    private final DeviceConfigRepository deviceConfig = new DeviceConfigRepository();

    private final AuthRegistry authRegistry = AuthRegistry.getInstance();


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

    public String checkAuthentication(String apiKey) {
        if (apiKey == null) {
            // THROW ERROR HERE!!
            return "API key cannot be null";
        }

        String hash = backendConfig.hashApiKey(apiKey);

        // 1. Check registry cache first
        String cached = authRegistry.getGatewayId(hash);
        if (cached != null) {
            logger.info("AuthRegistry cache hit");
            return cached;
        }

        // 2. Cache miss — existing logic unchanged
        String gatewayBackendId = backendConfig.validateApiKey(apiKey);
        if (!gatewayBackendId.startsWith("Invalid API key") && !gatewayBackendId.equals("API key cannot be null")) {
            authRegistry.putGatewayId(hash, gatewayBackendId);
            return gatewayBackendId;
        }

        String gatewayDeviceId = deviceConfig.validateApiKey(apiKey);
        if (!gatewayDeviceId.equals("Invalid API key") && !gatewayDeviceId.equals("API key cannot be null")) {
            authRegistry.putGatewayId(hash, gatewayDeviceId);
            return gatewayDeviceId;
        }

        // THROW ERROR HERE
        return "Invalid API key or API key cannot be null";
    }

}
