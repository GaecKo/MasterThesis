package edge_control.translation.queuing;

import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.adapter.DeviceAdapter;
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
 * Manages queuing configuration and enqueuing for all onboarded devices.
 * Owns both repositories and the QueueWorker.
 * QueueWorker is wired lazily via setDeviceRegistry() to avoid circular dependency.
 */
public class QueueRegistry {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static QueueRegistry instance;

    private final QueueConfigRepository   configRepo  = new QueueConfigRepository();
    private final QueuedRequestRepository requestRepo = new QueuedRequestRepository();

    // In-memory map for fast lookup — keyed by gatewayDeviceId
    private final Map<String, DeviceQueueConfig> queueConfigs = new HashMap<>();

    private QueueWorker queueWorker;

    private QueueRegistry() {}

    public static synchronized QueueRegistry getInstance() {
        if (instance == null) instance = new QueueRegistry();
        return instance;
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    /**
     * Called once at startup from DeviceRegistry constructor.
     * Wires the QueueWorker and reschedules retries for any requests
     * that were pending before the last shutdown.
     */
    public void init() {
        this.queueWorker = new QueueWorker(requestRepo, this,
                DeviceRegistry.getInstance());

        // Reschedule retries for devices that had pending requests before shutdown
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

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Load all persisted queue configs into memory.
     * Call this once at startup before init().
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
     * Cancels retry schedule, removes queue config and pending requests.
     */
    public void remove(String deviceId) throws EdgeControlException {
        if (!queueConfigs.containsKey(deviceId)) return;

        if (queueWorker != null) queueWorker.cancelRetries(deviceId);

        configRepo.delete(deviceId);
        requestRepo.deleteAllForDevice(deviceId);
        queueConfigs.remove(deviceId);

        logger.info("Queuing config and pending requests removed for device " + deviceId);
    }

    // ── Enqueue ───────────────────────────────────────────────────────────────

    /**
     * Saves a failed request to the queue and schedules retries if not already running.
     * Called by the filter when AdapterCallback.onDeviceUnreachable() fires.
     */
    public void enqueue(String deviceId, String body, Map<String, String> headers)
            throws EdgeControlException {
        if (!hasQueuing(deviceId)) {
            logger.log("Enqueue requested for device " + deviceId
                    + " but no queuing config found — request dropped");
            return;
        }

        QueuedRequest request = new QueuedRequest(deviceId, body, headers);
        requestRepo.save(request);
        logger.info("Request enqueued for device " + deviceId
                + " [id=" + request.getId() + "]");

        // Schedule retries — does nothing if already scheduled
        DeviceQueueConfig config = queueConfigs.get(deviceId);
        if (queueWorker != null) {
            queueWorker.scheduleRetries(deviceId, config.getRetryInterval());
        } else {
            logger.log("QueueWorker not yet initialised — retry will be scheduled on next init()");
        }
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    public DeviceQueueConfig getConfig(String deviceId) {
        return queueConfigs.get(deviceId);
    }

    public boolean hasQueuing(String deviceId) {
        return queueConfigs.containsKey(deviceId);
    }

    public Map<String, DeviceQueueConfig> getAllConfigs() {
        return Collections.unmodifiableMap(queueConfigs);
    }
}