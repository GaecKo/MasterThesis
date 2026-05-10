package edge_control.translation.registry;

import edge_control.auth.tokens.GatewayTokensRegistry;
import edge_control.database.DevicesTranslationConfigRepository;
import edge_control.translation.adapter.AdapterFactory;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.translation.config.DeviceConfig;
import edge_control.exceptions.EdgeControlException;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.queuing.QueueRegistry;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Singleton registry that manages all active device adapters.
 *
 * Periodically syncs with the database to detect config changes, rebuilding
 * adapters whose fingerprint has changed and removing those that no longer exist.
 * Also coordinates with QueueRegistry to onboard and remove queuing config.
 */
public class DeviceRegistry {

    private static DeviceRegistry instance;

    private final Map<String, DeviceAdapter> adapters       = new ConcurrentHashMap<>();
    private final Map<String, String>        fingerprints   = new ConcurrentHashMap<>();
    private final Map<String, DeviceConfig>  deviceConfigs  = new ConcurrentHashMap<>();

    private static final QueueRegistry queueRegistry = QueueRegistry.getInstance();

    private final AdapterFactory adapterFactory = new AdapterFactory();
    private final DevicesTranslationConfigRepository repository =
            new DevicesTranslationConfigRepository();

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();
    private static GatewayTokensRegistry tokensRegistry;


    private DeviceRegistry() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // TODO: make the refresh interval configurable
        scheduler.scheduleAtFixedRate(this::refresh, 5, 5, TimeUnit.SECONDS);

        try {
            tokensRegistry = GatewayTokensRegistry.getInstance();
        } catch (EdgeControlException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the singleton instance, creating it on first call.
     * On first creation: loads queue configs, wires the QueueWorker, and does an initial device load.
     */
    public static synchronized DeviceRegistry getInstance() {
        if (instance == null) {
            instance = new DeviceRegistry();
            queueRegistry.loadAll();
            queueRegistry.init();
            instance.refresh();
        }
        return instance;
    }

    // | ================= Lookup ================= |

    /**
     * @param deviceId Gateway device ID
     * @return The active adapter for the device, or null if not registered
     */
    public DeviceAdapter getAdapter(String deviceId) {
        return adapters.get(deviceId);
    }

    /**
     * @param deviceId Gateway device ID
     * @return The current DeviceConfig for the device, or null if not registered
     */
    public DeviceConfig getConfig(String deviceId) {
        return deviceConfigs.get(deviceId);
    }

    // | ================= Refresh ================= |

    /**
     * Syncs the registry with the current database state.
     * Rebuilds adapters whose fingerprint has changed, and removes those no longer in the DB.
     * If a rebuild fails, the device is deleted from the registry and DB to avoid a stale state.
     */
    public synchronized void refresh() {
        List<DeviceConfig> configs = repository.findAll();
        Set<String> seen = new HashSet<>();

        for (DeviceConfig config : configs) {
            String deviceId   = config.getDeviceId();
            String fingerprint = config.fingerprint();
            seen.add(deviceId);

            if (!fingerprint.equals(fingerprints.get(deviceId))) {
                try {
                    rebuild(config);
                } catch (EdgeControlException e) {
                    logger.error("Failed to rebuild device " + deviceId + ": " + e.getMessage());
                    logger.error("Removing device " + deviceId + " due to rebuild error");
                    try {
                        delete(deviceId);
                    } catch (EdgeControlException ex) {
                        logger.error("Failed to delete device " + deviceId + " after rebuild error: " + ex.getMessage());
                    }
                }
            }
        }

        // Remove adapters for devices that were deleted from the DB
        for (String deviceId : new HashSet<>(adapters.keySet())) {
            if (!seen.contains(deviceId)) {
                remove(deviceId);
            }
        }
    }

    // | ================= Upsert / Rebuild ================= |

    /**
     * Saves the config to the database and rebuilds the adapter.
     * If the rebuild fails, the device is deleted to avoid a partially initialised state.
     *
     * @param config Device configuration to save and apply
     * @throws EdgeControlException If the rebuild fails
     */
    public synchronized void upsert(DeviceConfig config) throws EdgeControlException {
        // get security object and delete from init config
        JSONObject deviceSecurity = config.getConfig().getJSONObject("security");
        config.getConfig().remove("security");

        // load security in token registry
        if (deviceSecurity != null) {
            // can fail: but handled at outer scope by Manager
            tokensRegistry.upsertToken(config.getDeviceId(), deviceSecurity);
        }

        // save config (without security)
        repository.save(config);
        try {
            rebuild(config);
        } catch (EdgeControlException e) {
            // hold consistent state by deleting failed built config
            delete(config.getDeviceId());
            throw e;
        }
    }

    /**
     * Removes any existing adapter for the device, then creates and initialises a new one.
     * Also onboards the queuing config if present.
     *
     * @param config Device configuration to apply
     * @throws EdgeControlException If adapter initialisation fails
     */
    public void rebuild(DeviceConfig config) throws EdgeControlException {
        String deviceId = config.getDeviceId();
        remove(deviceId);

        DeviceAdapter adapter = adapterFactory.create(config);
        // init may throw — caught by the caller (refresh or upsert)
        adapter.init(config);

        adapters.put(deviceId, adapter);
        deviceConfigs.put(deviceId, config);
        fingerprints.put(deviceId, config.fingerprint());

        queueRegistry.upsert(config);
    }

    // | ================= Remove / Delete ================= |

    /**
     * Removes the adapter from the in-memory registry and shuts it down.
     * Does not touch the database.
     *
     * @param deviceId Gateway device ID to remove
     */
    public void remove(String deviceId) {
        DeviceAdapter existing = adapters.remove(deviceId);
        if (existing != null) {
            existing.shutdown();
        }
        fingerprints.remove(deviceId);
        deviceConfigs.remove(deviceId);
    }

    /**
     * Deletes the device from the database, removes it from the registry,
     * and clears its queuing configuration.
     *
     * @param gatewayDeviceId Gateway device ID to delete
     * @return True if the document was found and deleted from the database
     * @throws EdgeControlException If the database delete fails
     */
    public boolean delete(String gatewayDeviceId) throws EdgeControlException {
        // Delete from DB first so the next refresh does not re-add it
        boolean deleted = repository.delete(gatewayDeviceId);
        tokensRegistry.deleteTokenEntry(gatewayDeviceId);
        remove(gatewayDeviceId);
        queueRegistry.remove(gatewayDeviceId);
        return deleted;
    }
}