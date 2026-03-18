package edge_control.translation.queuing;

import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.config.DeviceQueueConfig;
import edge_control.database.QueueConfigRepository;
import edge_control.database.QueuedRequestRepository;
import edge_control.translation.config.DeviceConfig;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages queuing configuration for all onboarded devices.
 * Owns QueueConfigRepository and QueuedRequestRepository.
 *
 * On startup, loads all persisted queue configs into an in-memory map
 * for fast lookup during request handling.
 */
public class QueueRegistry {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private final QueueConfigRepository   configRepo   = new QueueConfigRepository();
    private final QueuedRequestRepository requestRepo  = new QueuedRequestRepository();

    private static QueueRegistry instance;

    // In-memory map for fast lookup — keyed by gatewayDeviceId
    private final Map<String, DeviceQueueConfig> queueConfigs = new HashMap<>();

    private QueueRegistry() {}

    public static QueueRegistry getInstance() {
        if (instance == null) {
            instance = new QueueRegistry();
        }
        return instance;
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Load all persisted queue configs into memory.
     * Call this once at startup alongside DeviceRegistry.loadAll().
     */
    public void loadAll() {
        List<DeviceQueueConfig> configs = configRepo.findAll();
        for (DeviceQueueConfig config : configs) {
            queueConfigs.put(config.getGatewayDeviceId(), config);
        }
        logger.info("QueueRegistry loaded " + queueConfigs.size() + " queue config(s)");
    }

    // ── Upsert ────────────────────────────────────────────────────────────────

    /**
     * Called from DeviceRegistry.rebuild() when a device is onboarded or updated.
     * If the device config contains a "queuing" block, parses and persists it.
     * If no "queuing" block is present, silently does nothing.
     */
    public void upsert(DeviceConfig deviceConfig) throws EdgeControlException {
        String deviceId = deviceConfig.getDeviceId();
        JSONObject root = deviceConfig.getConfig();

        if (!root.has("queuing")) {
            logger.debug("No queuing config for device " + deviceId + " — skipping");
            return;
        }

        JSONObject queuingJson = root.getJSONObject("queuing");
        DeviceQueueConfig queueConfig = new DeviceQueueConfig(deviceId, queuingJson);

        configRepo.save(queueConfig);
        queueConfigs.put(deviceId, queueConfig);

        logger.info("Queuing config upserted for device " + deviceId
                + " — retryInterval=" + queueConfig.getRetryInterval().getSeconds() + "s"
                + " maxTTL=" + queueConfig.getMaxTimeToLive().getSeconds() + "s"
                + " callbackUrl=" + queueConfig.getCallbackUrl());
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    /**
     * Called from DeviceRegistry.remove() when a device is offboarded.
     * Removes queue config and any pending queued requests for that device.
     */
    public void remove(String deviceId) throws EdgeControlException {
        if (!queueConfigs.containsKey(deviceId)) return;

        configRepo.delete(deviceId);
        requestRepo.deleteAllForDevice(deviceId);
        queueConfigs.remove(deviceId);

        logger.info("Queuing config and pending requests removed for device " + deviceId);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /**
     * Returns the queue config for a device, or null if queuing is not configured.
     */
    public DeviceQueueConfig getConfig(String deviceId) {
        return queueConfigs.get(deviceId);
    }

    public boolean hasQueuing(String deviceId) {
        return queueConfigs.containsKey(deviceId);
    }
}
