package edge_control.translation;

import edge_control.translation.adapter.DeviceAdapter;
import edge_control.translation.config.DeviceConfig;
import edge_control.translation.registry.DeviceRegistry;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import org.json.JSONObject;

/**
 * Facade over DeviceRegistry that exposes device lifecycle operations
 * to the onboarding filter, accepting raw JSON request bodies as input.
 */
public class DeviceTranslationManager {

    private static DeviceTranslationManager instance;
    private final DeviceRegistry deviceRegistry;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private DeviceTranslationManager(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized DeviceTranslationManager getInstance() {
        if (instance == null) {
            instance = new DeviceTranslationManager(DeviceRegistry.getInstance());
        }
        return instance;
    }

    // | ================= Device operations ================= |

    /**
     * Parses the request body and upserts the device adapter into the registry.
     *
     * @param requestBody JSON string containing at least 'gatewayDeviceId' and 'adapter'
     * @throws CorruptedConfiguration If the JSON is invalid or required fields are missing
     * @throws EdgeControlException   If the adapter initialisation fails
     */
    public void createAdapter(String requestBody) throws CorruptedConfiguration, EdgeControlException {
        JSONObject config = parseBody(requestBody);

        if (!config.has("adapter")) {
            throw new CorruptedConfiguration("Missing required field: 'adapter'");
        }
        if (!config.has("gatewayDeviceId")) {
            throw new CorruptedConfiguration("Missing required field: 'gatewayDeviceId'");
        }

        String deviceId = config.getString("gatewayDeviceId");
        logger.info("Creating adapter for device: " + deviceId);

        deviceRegistry.upsert(new DeviceConfig(config));
    }

    /**
     * Returns the active adapter for a device.
     *
     * @param deviceId Gateway device ID
     * @return The active DeviceAdapter, or null if not registered
     */
    public DeviceAdapter get(String deviceId) {
        return deviceRegistry.getAdapter(deviceId);
    }

    /**
     * Parses the request body and retrieves the device's current configuration.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId'
     * @return Current DeviceConfig, or null if the device is not registered
     * @throws CorruptedConfiguration If the JSON is invalid or 'gatewayDeviceId' is missing
     */
    public DeviceConfig getConfig(String requestBody) throws CorruptedConfiguration {
        JSONObject config = parseBody(requestBody);

        if (!config.has("gatewayDeviceId")) {
            throw new CorruptedConfiguration("Missing required field: 'gatewayDeviceId'");
        }

        String gatewayDeviceId = config.getString("gatewayDeviceId");
        logger.info("Retrieving config for device: " + gatewayDeviceId);
        return deviceRegistry.getConfig(gatewayDeviceId);
    }

    /**
     * Parses the request body and deletes the device from the registry and database.
     *
     * @param requestBody JSON string containing 'gatewayDeviceId'
     * @return True if the device was found and deleted from the database
     * @throws CorruptedConfiguration If the JSON is invalid or 'gatewayDeviceId' is missing
     * @throws EdgeControlException   If the database delete fails
     */
    public boolean deleteDeviceConfig(String requestBody) throws EdgeControlException, CorruptedConfiguration {
        JSONObject config = parseBody(requestBody);

        if (!config.has("gatewayDeviceId")) {
            throw new CorruptedConfiguration("Missing required field: 'gatewayDeviceId'");
        }

        String gatewayDeviceId = config.getString("gatewayDeviceId");
        logger.info("Deleting config for device: " + gatewayDeviceId);
        return deviceRegistry.delete(gatewayDeviceId);
    }

    // | ================= Internal helpers ================= |

    /**
     * Parses a JSON request body, wrapping parse errors as CorruptedConfiguration.
     *
     * @param requestBody Raw JSON string
     * @return Parsed JSONObject
     * @throws CorruptedConfiguration If the string is not valid JSON
     */
    private JSONObject parseBody(String requestBody) throws CorruptedConfiguration {
        try {
            return new JSONObject(requestBody);
        } catch (Exception e) {
            throw new CorruptedConfiguration(
                    "Unable to parse request body as JSON: " + e.getMessage());
        }
    }
}