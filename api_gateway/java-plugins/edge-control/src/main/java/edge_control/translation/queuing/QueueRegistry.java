package edge_control.translation.queuing;

import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.config.DeviceQueueConfig;
import edge_control.database.QueueConfigRepository;
import edge_control.database.QueuedRequestRepository;
import edge_control.translation.config.DeviceConfig;
import edge_control.translation.queuing.model.QueuedRequest;
import edge_control.translation.registry.DeviceRegistry;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton registry that manages queuing configuration and request enqueuing for all devices.
 * Owns both the config and request repositories and coordinates with QueueWorker for retries.
 *
 * Startup order: loadAll() must be called before init(), which wires the QueueWorker.
 */
public class QueueRegistry {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static QueueRegistry instance;

    private final QueueConfigRepository   configRepo  = new QueueConfigRepository();
    private final QueuedRequestRepository requestRepo = new QueuedRequestRepository();

    // In-memory map for fast lookup during request handling, keyed by gatewayDeviceId
    private final Map<String, DeviceQueueConfig> queueConfigs = new HashMap<>();

    private QueueWorker queueWorker;

    private QueueRegistry() {}

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized QueueRegistry getInstance() {
        if (instance == null) instance = new QueueRegistry();
        return instance;
    }

    // | ================= Startup ================= |

    /**
     * Loads all persisted queue configs into the in-memory map.
     * Must be called before init().
     */
    public void loadAll() {
        List<DeviceQueueConfig> configs = configRepo.findAll();
        for (DeviceQueueConfig config : configs) {
            queueConfigs.put(config.getGatewayDeviceId(), config);
        }
        logger.info("QueueRegistry loaded " + queueConfigs.size() + " queue config(s)");
    }

    /**
     * Wires the QueueWorker and reschedules retries for any requests that were
     * pending before the last shutdown. Must be called after loadAll().
     */
    public void init() {
        this.queueWorker = new QueueWorker(requestRepo, this, DeviceRegistry.getInstance());

        // Restore retry schedules for devices that had pending requests before shutdown
        for (Map.Entry<String, DeviceQueueConfig> entry : queueConfigs.entrySet()) {
            String deviceId = entry.getKey();
            List<QueuedRequest> pending = requestRepo.findAllForDevice(deviceId);
            if (!pending.isEmpty()) {
                logger.info("Rescheduling retries for device " + deviceId
                        + " (" + pending.size() + " pending request(s) from before shutdown)");
                queueWorker.scheduleRetries(deviceId, entry.getValue().getRetryInterval());
            }
        }

        logger.info("QueueRegistry initialised");
    }

    // | ================= Upsert / Remove ================= |

    /**
     * Parses and persists the queuing config for a device if a 'queuing' block is present.
     * Silently does nothing if the block is absent.
     * Called from DeviceRegistry.rebuild() on onboarding or config update.
     *
     * @param deviceConfig Full device config, may or may not contain a 'queuing' block
     * @throws EdgeControlException If the config is invalid or the DB write fails
     */
    public void upsert(DeviceConfig deviceConfig) throws EdgeControlException {
        String deviceId = deviceConfig.getDeviceId();
        JSONObject root = deviceConfig.getConfig();

        if (!root.has("queuing")) {
            logger.debug("No queuing config for device " + deviceId + " - skipping");
            return;
        }

        JSONObject queuingJson = root.getJSONObject("queuing");
        DeviceQueueConfig queueConfig = new DeviceQueueConfig(deviceId, queuingJson);

        configRepo.save(queueConfig);
        queueConfigs.put(deviceId, queueConfig);

        logger.info("Queuing config upserted for device " + deviceId
                + " - retryInterval=" + queueConfig.getRetryInterval().getSeconds() + "s"
                + " maxTTL=" + queueConfig.getMaxTimeToLive().getSeconds() + "s");
    }

    /**
     * Cancels the retry schedule and removes all queuing state for a device.
     * Called from DeviceRegistry.remove() on offboarding.
     *
     * @param deviceId Gateway device ID to remove
     * @throws EdgeControlException If the DB delete fails
     */
    public void remove(String deviceId) throws EdgeControlException {
        if (!queueConfigs.containsKey(deviceId)) return;

        if (queueWorker != null) queueWorker.cancelRetries(deviceId);

        configRepo.delete(deviceId);
        requestRepo.deleteAllForDevice(deviceId);
        queueConfigs.remove(deviceId);

        logger.info("Queuing config and pending requests removed for device " + deviceId);
    }

    // | ================= Enqueue ================= |

    /**
     * Saves a failed request to the queue and schedules retries if not already running.
     * Called by the filter when AdapterCallback.onDeviceUnreachable() fires.
     *
     * @param deviceId         Gateway device ID the request was destined for
     * @param callbackEndpoint Backend URL to notify when the request is eventually delivered
     * @param body             Original request body
     * @param headers          Original request headers
     * @return The ID of the enqueued request, or null if no queuing config exists for the device
     * @throws EdgeControlException If the DB write fails
     */
    public String enqueue(String deviceId, String callbackEndpoint,
                          String body, Map<String, String> headers) throws EdgeControlException {
        if (!hasQueuing(deviceId)) {
            logger.warn("Enqueue requested for device " + deviceId
                    + " but no queuing config found - request dropped");
            return null;
        }

        QueuedRequest request = new QueuedRequest(deviceId, callbackEndpoint, body, headers);
        requestRepo.save(request);
        logger.info("Request enqueued for device " + deviceId + " [id=" + request.getId() + "]");

        // scheduleRetries is a no-op if a task is already running for this device
        DeviceQueueConfig config = queueConfigs.get(deviceId);
        if (queueWorker != null) {
            queueWorker.scheduleRetries(deviceId, config.getRetryInterval());
        } else {
            logger.warn("QueueWorker not yet initialised - retry will be scheduled on next init()");
        }

        return request.getId();
    }

    // | ================= Lookup ================= |

    /**
     * @param deviceId Gateway device ID
     * @return The queue config for the device, or null if not configured
     */
    public DeviceQueueConfig getConfig(String deviceId) {
        return queueConfigs.get(deviceId);
    }

    /**
     * @param deviceId Gateway device ID
     * @return True if queuing is configured for the device
     */
    public boolean hasQueuing(String deviceId) {
        return queueConfigs.containsKey(deviceId);
    }

    /**
     * @return Unmodifiable view of all queue configs, used by QueueWorker for iteration
     */
    public Map<String, DeviceQueueConfig> getAllConfigs() {
        return Collections.unmodifiableMap(queueConfigs);
    }
}