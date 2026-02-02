package protocol_translation.device.registry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import protocol_translation.device.adapter.DeviceAdapter;
import protocol_translation.device.config.DeviceConfig;
import protocol_translation.device.adapter.AdapterFactory;
import protocol_translation.database.DeviceConfigRepository;
import protocol_translation.logger.ProtocolTranslationLogger;

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

    private final AdapterFactory adapterFactory = new AdapterFactory();
    private final DeviceConfigRepository repository = new DeviceConfigRepository();

    private static final ProtocolTranslationLogger logger = ProtocolTranslationLogger.getInstance();

    /**
     * Private constructor that initializes the singleton and schedules
     * periodic refreshes of the device registry.
     */
    private DeviceRegistry() {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();

        // TODO: change delay to something else
        scheduler.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                },
                5,          // initial delay
                5,          // period
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
                logger.info("Rebuilding device: " + deviceId);
                rebuild(deviceId, config, fingerprint);
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
     * @param deviceId the ID of the device
     * @param config the configuration to use
     * @param fingerprint the configuration fingerprint
     */
    public void rebuild(String deviceId,
                        DeviceConfig config,
                        String fingerprint) {
        remove(deviceId);

        DeviceAdapter adapter = adapterFactory.create(config.getAdapter());
        try {
            adapter.init(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to init device " + deviceId, e);
        }

        adapters.put(deviceId, adapter);
        fingerprints.put(deviceId, fingerprint);
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
    }

    /**
     * Inserts or updates a device configuration in the repository
     * and rebuilds the corresponding adapter.
     *
     * @param config the device configuration to upsert
     */
    public synchronized void upsert(DeviceConfig config) {
        repository.save(config);

        String deviceId = config.getDeviceId();
        String fingerprint = config.fingerprint();

        rebuild(deviceId, config, fingerprint);
    }

}
