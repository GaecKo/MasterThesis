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

public class AuthRegistry {

    private static AuthRegistry instance;
    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    // apiKeyHash → gatewayId
    private final Map<String, String> apiKeyCache = new ConcurrentHashMap<>();

    // gatewayBackendId → listOfAuthorizations Document
    private final Map<String, Document> backendAuthCache = new ConcurrentHashMap<>();

    // gatewayDeviceId → listOfAuthorizations
    private final Map<String, List<String>> deviceAuthCache = new ConcurrentHashMap<>();

    // gatewayDeviceId → { gatewayBackendId → endpoint }
    private final Map<String, Map<String, String>> deviceEndpointsCache = new ConcurrentHashMap<>();

    // gatewayBackendId → callbackEndpoint
    private final Map<String, String> backendCallbackEndpointsCache = new ConcurrentHashMap<>();

    private final BackendConfigRepository backendConfig = new BackendConfigRepository();
    private final DeviceConfigRepository deviceConfig = new DeviceConfigRepository();
    private final BackendAuthorizationsRepository backendAuths = new BackendAuthorizationsRepository();
    private final DeviceAuthorizationsRepository deviceAuths = new DeviceAuthorizationsRepository();

    private AuthRegistry() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::refresh, 5, 30, TimeUnit.SECONDS);
        logger.info("AuthRegistry initialized");
    }

    public static AuthRegistry getInstance() {
        if (instance == null) {
            instance = new AuthRegistry();
        }
        return instance;
    }

    public synchronized void refresh() {
        apiKeyCache.clear();
        backendConfig.findAll().forEach(doc -> {
                String hash = doc.getString("apiKeyHash");
                String id = doc.getString("gatewayBackendId");
                if (hash != null && id != null) apiKeyCache.put(hash, id);
        });
        deviceConfig.findAll().forEach(doc -> {
                String hash = doc.getString("apiKeyHash");
                String id = doc.getString("gatewayDeviceId");
                if (hash != null && id != null) apiKeyCache.put(hash, id);
        });

        backendAuthCache.clear();
        backendAuths.findAll().forEach(doc -> {
                String id = doc.getString("gatewayBackendId");
                Document auths = (Document) doc.get("listOfAuthorizations");
                if (id != null && auths != null) backendAuthCache.put(id, auths);
        });

        deviceAuthCache.clear();
        deviceAuths.findAll().forEach(doc -> {
                String id = doc.getString("gatewayDeviceId");
                List<String> auths = doc.getList("listOfAuthorizations", String.class);
                if (id != null && auths != null) deviceAuthCache.put(id, auths);
        });

        deviceEndpointsCache.clear();
        deviceAuths.findAll().forEach(doc -> {
            String deviceId = doc.getString("gatewayDeviceId");
            List<String> auths = doc.getList("listOfAuthorizations", String.class);
            if (deviceId != null && auths != null) {
                Map<String, String> endpointsMap = new ConcurrentHashMap<>();
                for (String backendId : auths) {
                    Document backendDoc = backendConfig.findBackendById(backendId);
                    if (backendDoc != null) {
                        String endpoint = backendDoc.getString("infoEndpoint");
                        if (endpoint != null) {
                            endpointsMap.put(backendId, endpoint);
                        }
                    }
                }
                deviceEndpointsCache.put(deviceId, endpointsMap);
            }
        });

        backendCallbackEndpointsCache.clear();
        backendConfig.findAll().forEach(doc -> {
            String backendId = doc.getString("gatewayBackendId");
            String callbackEndpoint = doc.getString("callbackEndpoint");
            if (backendId != null && callbackEndpoint != null) {
                backendCallbackEndpointsCache.put(backendId, callbackEndpoint);
            }
        });

        logger.info(this.getCallbackEndpoint("backend_c89f031b-3971-49fd-8640-dad58af970f4"));
    }


    public String getGatewayId(String apiKeyHash) {
            return apiKeyCache.get(apiKeyHash);
    }

    public void putGatewayId(String apiKeyHash, String gatewayId) {
        apiKeyCache.put(apiKeyHash, gatewayId);
    }

    public Document getBackendAuth(String gatewayBackendId) {
        return backendAuthCache.get(gatewayBackendId);
    }

    public void putBackendAuth(String gatewayBackendId, Document d){
        backendAuthCache.put(gatewayBackendId, d);
    }

    public List<String> getDeviceAuth(String gatewayDeviceId) {
        return deviceAuthCache.get(gatewayDeviceId);
    }

    public void putDeviceAuth(String gatewayDeviceId, List<String> l){
        deviceAuthCache.put(gatewayDeviceId, l);
    }

    public String getCallbackEndpoint(String gatewayBackendId) {return backendCallbackEndpointsCache.get(gatewayBackendId); }

    public Map<String, String> getDeviceEndpoints(String gatewayDeviceId) {
        return deviceEndpointsCache.get(gatewayDeviceId);
    }

    public void putDeviceEndpoints(String gatewayDeviceId, Map<String, String> endpoints) {
        deviceEndpointsCache.put(gatewayDeviceId, endpoints);
    }

}