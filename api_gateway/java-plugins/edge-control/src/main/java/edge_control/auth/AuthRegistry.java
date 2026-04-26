package edge_control.auth;

import edge_control.database.*;
import edge_control.logger.EdgeControlLogger;
import org.bson.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Singleton in-memory cache for authentication and authorization data.
 *
 * Periodically refreshes from MongoDB so filters can perform auth checks
 * without hitting the database on every request.
 *
 * Caches:
 * - apiKeyHash → gatewayId (backend or device)
 * - gatewayBackendId → listOfAuthorizations Document
 * - gatewayDeviceId → list of authorized backend IDs
 * - gatewayDeviceId → { gatewayBackendId → infoEndpoint }
 * - gatewayBackendId → callbackEndpoint
 */
public class AuthRegistry {

    private static AuthRegistry instance;

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    // apiKeyHash → gatewayId (backend_ or device_ prefixed)
    private final Map<String, String> apiKeyCache = new ConcurrentHashMap<>();

    // gatewayBackendId → listOfAuthorizations Document (deviceId → [commands])
    private final Map<String, Document> backendAuthCache = new ConcurrentHashMap<>();

    // gatewayDeviceId → list of authorized gatewayBackendIds
    private final Map<String, List<String>> deviceAuthCache = new ConcurrentHashMap<>();

    // gatewayDeviceId → { gatewayBackendId → infoEndpoint }
    private final Map<String, Map<String, String>> deviceEndpointsCache = new ConcurrentHashMap<>();

    // gatewayBackendId → callbackEndpoint (used by queuing layer for retry notifications)
    private final Map<String, String> backendCallbackEndpointsCache = new ConcurrentHashMap<>();

    private final BackendConfigRepository        backendConfig = new BackendConfigRepository();
    private final DeviceConfigRepository         deviceConfig  = new DeviceConfigRepository();
    private final BackendAuthorizationsRepository backendAuths = new BackendAuthorizationsRepository();
    private final DeviceAuthorizationsRepository  deviceAuths  = new DeviceAuthorizationsRepository();

    private AuthRegistry() {
        // Schedule a periodic full refresh from the database
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::refresh, 5, 30, TimeUnit.SECONDS);
        logger.info("AuthRegistry initialized");
    }

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized AuthRegistry getInstance() {
        if (instance == null) {
            instance = new AuthRegistry();
        }
        return instance;
    }

    // | ================= Cache refresh ================= |

    /**
     * Fully rebuilds all caches from the current database state.
     * Called periodically by the scheduler and after any mutation.
     *
     * backendConfig.findAll() and deviceAuths.findAll() are each called only once
     * per refresh to avoid redundant DB round-trips.
     */
    public synchronized void refresh() {
        // Rebuild API key cache from both backends and devices in one pass each
        apiKeyCache.clear();
        List<Document> backendDocs = backendConfig.findAll();
        backendDocs.forEach(doc -> {
            String hash = doc.getString("apiKeyHash");
            String id   = doc.getString("gatewayBackendId");
            if (hash != null && id != null) apiKeyCache.put(hash, id);
        });
        deviceConfig.findAll().forEach(doc -> {
            String hash = doc.getString("apiKeyHash");
            String id   = doc.getString("gatewayDeviceId");
            if (hash != null && id != null) apiKeyCache.put(hash, id);
        });

        // Rebuild backend authorization cache
        backendAuthCache.clear();
        backendAuths.findAll().forEach(doc -> {
            String id     = doc.getString("gatewayBackendId");
            Document auths = (Document) doc.get("listOfAuthorizations");
            if (id != null && auths != null) backendAuthCache.put(id, auths);
        });

        // Rebuild device authorization cache and device endpoint cache in a single pass
        deviceAuthCache.clear();
        deviceEndpointsCache.clear();
        deviceAuths.findAll().forEach(doc -> {
            String id             = doc.getString("gatewayDeviceId");
            List<String> auths    = doc.getList("listOfAuthorizations", String.class);
            if (id == null || auths == null) return;

            deviceAuthCache.put(id, auths);

            // For each authorized backend, look up its infoEndpoint
            Map<String, String> endpointsMap = new ConcurrentHashMap<>();
            for (String backendId : auths) {
                Document backendDoc = backendConfig.findBackendById(backendId);
                if (backendDoc != null) {
                    String endpoint = backendDoc.getString("infoEndpoint");
                    if (endpoint != null) endpointsMap.put(backendId, endpoint);
                }
            }
            deviceEndpointsCache.put(id, endpointsMap);
        });

        // Rebuild callback endpoint cache from the already-fetched backend docs
        backendCallbackEndpointsCache.clear();
        backendDocs.forEach(doc -> {
            String backendId       = doc.getString("gatewayBackendId");
            String callbackEndpoint = doc.getString("callbackEndpoint");
            if (backendId != null && callbackEndpoint != null) {
                backendCallbackEndpointsCache.put(backendId, callbackEndpoint);
            }
        });
    }

    // | ================= Lookups ================= |

    /**
     * @param apiKeyHash SHA-256 hash of the API key from the request header
     * @return The gatewayId (backend_ or device_ prefixed) that owns this key, or null
     */
    public String getGatewayId(String apiKeyHash) {
        return apiKeyCache.get(apiKeyHash);
    }

    /**
     * @param gatewayBackendId Backend to look up
     * @return Authorization document mapping deviceId to list of allowed commands, or null
     */
    public Document getBackendAuth(String gatewayBackendId) {
        return backendAuthCache.get(gatewayBackendId);
    }

    /**
     * @param gatewayDeviceId Device to look up
     * @return List of backend IDs authorized to send commands to this device, or null
     */
    public List<String> getDeviceAuth(String gatewayDeviceId) {
        return deviceAuthCache.get(gatewayDeviceId);
    }

    /**
     * @param gatewayDeviceId Device to look up
     * @return Map of gatewayBackendId to infoEndpoint for all backends authorized for this device
     */
    public Map<String, String> getDeviceEndpoints(String gatewayDeviceId) {
        return deviceEndpointsCache.get(gatewayDeviceId);
    }

    /**
     * @param gatewayBackendId Backend to look up
     * @return The callback endpoint for retry notifications, or null if not configured
     */
    public String getCallbackEndpoint(String gatewayBackendId) {
        return backendCallbackEndpointsCache.get(gatewayBackendId);
    }

    // | ================= Cache mutations ================= |

    /** Updates the API key cache entry for a gateway ID. */
    public void putGatewayId(String apiKeyHash, String gatewayId) {
        apiKeyCache.put(apiKeyHash, gatewayId);
    }

    /** Updates the backend authorization cache entry. */
    public void putBackendAuth(String gatewayBackendId, Document d) {
        backendAuthCache.put(gatewayBackendId, d);
    }

    /** Updates the device authorization cache entry. */
    public void putDeviceAuth(String gatewayDeviceId, List<String> l) {
        deviceAuthCache.put(gatewayDeviceId, l);
    }

    /** Updates the device endpoints cache entry. */
    public void putDeviceEndpoints(String gatewayDeviceId, Map<String, String> endpoints) {
        deviceEndpointsCache.put(gatewayDeviceId, endpoints);
    }
}