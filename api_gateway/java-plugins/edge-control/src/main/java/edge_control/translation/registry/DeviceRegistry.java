package edge_control.translation.registry;

import edge_control.database.DevicesTranslationConfigRepository;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.translation.adapter.AdapterFactory;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.translation.config.DeviceConfig;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.QueueRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Singleton registry that manages all device adapters in the system.
 *
 * Responsibilities:
 * - Maintains active DeviceAdapter instances keyed by device ID.
 * - Tracks device fingerprints to detect configuration changes.
 * - Periodically refreshes adapters based on the repository state.
 * - Handles creation, update (rebuild), and removal of adapters.
 *
 * Provides thread-safe methods to get adapters, refresh the registry,
 * upsert device configurations, and remove adapters.
 */
public class DeviceRegistry {

    private static DeviceRegistry instance;

    private final Map<String, DeviceAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();
    private final Map<String, DeviceConfig> deviceConfigs = new ConcurrentHashMap<>();

    private final QueueRegistry queueRegistry = QueueRegistry.getInstance();

    private final AdapterFactory adapterFactory = new AdapterFactory();
    private final DevicesTranslationConfigRepository repository = new DevicesTranslationConfigRepository();

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Private constructor that initializes the singleton and schedules
     * periodic refreshes of the device registry.
     */
    private DeviceRegistry() {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();

        // TODO: change delay to something else
        scheduler.scheduleAtFixedRate(
                this::refresh,
                5,              // initial delay
                5,                        // period
                TimeUnit.SECONDS
        );
    }

    /**
     * Returns the singleton instance of the DeviceRegistry,
     * triggering an initial refresh if necessary.
     *
     * @return DeviceRegistry singleton
     */
    public static DeviceRegistry getInstance() {
        if (instance == null) {
            instance = new DeviceRegistry();
        }
        instance.refresh();
        return instance;
    }

    /**
     * Retrieves the adapter for a given device ID.
     *
     * @param deviceId the ID of the device
     * @return DeviceAdapter instance, or null if not registered
     */
    public DeviceAdapter get(String deviceId) {
        return adapters.get(deviceId);
    }

    public DeviceConfig getConfig(String deviceId) {
        return deviceConfigs.get(deviceId);
    }

    /**
     * Synchronizes the registry with the current repository state.
     * - Updates adapters whose configuration fingerprint has changed.
     * - Removes adapters that no longer exist in the repository.
     */
    public synchronized void refresh() {
        List<DeviceConfig> configs = repository.findAll();
        Set<String> seen = new HashSet<>();

        for (DeviceConfig config : configs) {
            String deviceId = config.getDeviceId();
            String fingerprint = config.fingerprint();

            // logger.info("Syncing device: " + deviceId);

            seen.add(deviceId);

            if (!fingerprint.equals(fingerprints.get(deviceId))) {
                try {
                    rebuild(config);
                } catch (EdgeControlException e) {
                    logger.error(e.getMessage());
                    logger.error("Removing config for device " + deviceId + " due to error (refresh)");
                    try {
                        delete(config.getDeviceId());
                    } catch (EdgeControlException ex) {
                        logger.error(ex.getMessage());
                        logger.error("Unable to remove config of " + deviceId + " due to error (refresh)");
                    }
                }

            }
        }

        // Handle deletions
        for (String deviceId : new HashSet<>(adapters.keySet())) {
            if (!seen.contains(deviceId)) {
                remove(deviceId);
            }
        }
    }

    /**
     * Rebuilds a device adapter using the provided configuration and fingerprint.
     * - Removes any existing adapter for the device.
     * - Creates and initializes a new adapter.
     * - Updates the fingerprints and adapters maps.
     *
     * @param config the configuration to use
     */
    public void rebuild(DeviceConfig config) throws EdgeControlException {

        String deviceId = config.getDeviceId();
        remove(deviceId);

        DeviceAdapter adapter = adapterFactory.create(config);
        try {
            adapter.init(config);
        } catch (EdgeControlException e) {
            throw e;
        }

        // logger.debug("Rebuilding device: " + deviceId + "\n" + config);

        adapters.put(deviceId, adapter);
        deviceConfigs.put(deviceId, config);
        fingerprints.put(deviceId, config.fingerprint());

        // Onboard queuing settings
        queueRegistry.upsert(config);

    }

    /**
     * Removes a device adapter from the registry and shuts it down.
     *
     * @param deviceId the ID of the device to remove
     */
    public void remove(String deviceId) {
        DeviceAdapter existing = adapters.remove(deviceId);
        if (existing != null) {
            existing.shutdown();
        }
        fingerprints.remove(deviceId);
        deviceConfigs.remove(deviceId);
    }

    public boolean delete(String gatewayDeviceId) throws EdgeControlException {
        // remove from db first (so deviceRegistry can't fetch it anymore)
        boolean db_del = repository.delete(gatewayDeviceId);

        // remove from deviceRegistry - optional, will be updated by refresh anyway
        remove(gatewayDeviceId);

        return db_del;
    }

    /**
     * Inserts or updates a device configuration in the repository
     * and rebuilds the corresponding adapter.
     *
     * @param config the device configuration to upsert
     */
    public synchronized void upsert(DeviceConfig config) throws EdgeControlException {
        repository.save(config);

        try {
            rebuild(config);
        } catch (EdgeControlException e) {
            delete(config.getDeviceId());
            throw e;
        }
    }

}
