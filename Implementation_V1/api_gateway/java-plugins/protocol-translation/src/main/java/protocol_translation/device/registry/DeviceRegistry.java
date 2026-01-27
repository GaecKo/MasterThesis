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

public class DeviceRegistry {

    private static DeviceRegistry instance;

    private final Map<String, DeviceAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();

    private final AdapterFactory adapterFactory = new AdapterFactory();
    private final DeviceConfigRepository repository = new DeviceConfigRepository();

    private static final ProtocolTranslationLogger logger = ProtocolTranslationLogger.getInstance();

    private DeviceRegistry() {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();

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

    public static DeviceRegistry getInstance() {
        if (instance == null) {
            instance = new DeviceRegistry();
        }
        instance.refresh();
        return instance;
    }

    public DeviceAdapter get(String deviceId) {
        return adapters.get(deviceId);
    }

    public synchronized void refresh() {
        List<DeviceConfig> configs = repository.findAll();
        Set<String> seen = new HashSet<>();

        for (DeviceConfig config : configs) {
            String deviceId = config.getDeviceId();
            String fingerprint = config.fingerprint();

            seen.add(deviceId);

            if (!fingerprint.equals(fingerprints.get(deviceId))) {
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

    public void remove(String deviceId) {
        DeviceAdapter existing = adapters.remove(deviceId);
        if (existing != null) {
            existing.shutdown();
        }
        fingerprints.remove(deviceId);
    }

    public synchronized void upsert(DeviceConfig config) {
        repository.save(config);

        String deviceId = config.getDeviceId();
        String fingerprint = config.fingerprint();

        rebuild(deviceId, config, fingerprint);
    }

}
