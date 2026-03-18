package edge_control.translation.queuing.config;

import edge_control.exceptions.CorruptedConfiguration;
import org.json.JSONObject;

import java.time.Duration;

/**
 * Parsed queuing settings for a single device.
 * Constructed from the "queuing" block of the device translation config.
 */
public class DeviceQueueConfig {

    private final String gatewayDeviceId;
    private final Duration retryInterval;
    private final Duration maxTimeToLive;
    private final String callbackUrl;

    public DeviceQueueConfig(String gatewayDeviceId, JSONObject queuingJson)
            throws CorruptedConfiguration {

        this.gatewayDeviceId = gatewayDeviceId;

        int retryIntervalSeconds = queuingJson.optInt("retryIntervalSeconds", 0);
        if (retryIntervalSeconds <= 0) {
            throw new CorruptedConfiguration(
                    "Queuing config for device " + gatewayDeviceId
                            + ": 'retryIntervalSeconds' must be a positive integer");
        }
        this.retryInterval = Duration.ofSeconds(retryIntervalSeconds);

        int maxTtlSeconds = queuingJson.optInt("maxTimeToLiveSeconds", 0);
        if (maxTtlSeconds <= 0) {
            throw new CorruptedConfiguration(
                    "Queuing config for device " + gatewayDeviceId
                            + ": 'maxTimeToLiveSeconds' must be a positive integer");
        }
        this.maxTimeToLive = Duration.ofSeconds(maxTtlSeconds);

        this.callbackUrl = queuingJson.optString("callbackUrl", null);
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new CorruptedConfiguration(
                    "Queuing config for device " + gatewayDeviceId
                            + ": 'callbackUrl' is missing or empty");
        }
    }

    /**
     * Reconstruct from a MongoDB document (already parsed as JSONObject).
     */
    public DeviceQueueConfig(JSONObject doc) throws CorruptedConfiguration {
        this.gatewayDeviceId = doc.optString("gatewayDeviceId", null);
        if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
            throw new CorruptedConfiguration(
                    "Queuing config document is missing 'gatewayDeviceId'");
        }

        int retryIntervalSeconds = doc.optInt("retryIntervalSeconds", 0);
        if (retryIntervalSeconds <= 0) {
            throw new CorruptedConfiguration(
                    "Queuing config for device " + gatewayDeviceId
                            + ": 'retryIntervalSeconds' must be a positive integer");
        }
        this.retryInterval = Duration.ofSeconds(retryIntervalSeconds);

        int maxTtlSeconds = doc.optInt("maxTimeToLiveSeconds", 0);
        if (maxTtlSeconds <= 0) {
            throw new CorruptedConfiguration(
                    "Queuing config for device " + gatewayDeviceId
                            + ": 'maxTimeToLiveSeconds' must be a positive integer");
        }
        this.maxTimeToLive = Duration.ofSeconds(maxTtlSeconds);

        this.callbackUrl = doc.optString("callbackUrl", null);
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new CorruptedConfiguration(
                    "Queuing config for device " + gatewayDeviceId
                            + ": 'callbackUrl' is missing or empty");
        }
    }

    public JSONObject toDocument() {
        JSONObject doc = new JSONObject();
        doc.put("gatewayDeviceId",      gatewayDeviceId);
        doc.put("retryIntervalSeconds", (int) retryInterval.getSeconds());
        doc.put("maxTimeToLiveSeconds", (int) maxTimeToLive.getSeconds());
        doc.put("callbackUrl",          callbackUrl);
        return doc;
    }

    public String getGatewayDeviceId()  { return gatewayDeviceId; }
    public Duration getRetryInterval()  { return retryInterval; }
    public Duration getMaxTimeToLive()  { return maxTimeToLive; }
    public String getCallbackUrl()      { return callbackUrl; }
}
