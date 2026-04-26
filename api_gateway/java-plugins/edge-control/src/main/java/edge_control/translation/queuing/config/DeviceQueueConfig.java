package edge_control.translation.queuing.config;

import edge_control.exceptions.CorruptedConfiguration;
import org.json.JSONObject;

import java.time.Duration;

/**
 * Parsed queuing settings for a single device.
 * Constructed from the 'queuing' block of the device translation config,
 * or reconstructed from a persisted MongoDB document.
 */
public class DeviceQueueConfig {

    private final String   gatewayDeviceId;
    private final Duration retryInterval;
    private final Duration maxTimeToLive;

    // | ================= Constructors ================= |

    /**
     * Parses queuing settings from the 'queuing' block of a device config.
     *
     * @param gatewayDeviceId Device this config belongs to, used in error messages
     * @param queuingJson     The 'queuing' JSON block from the device config
     * @throws CorruptedConfiguration If retryIntervalSeconds or maxTimeToLiveSeconds are missing or non-positive
     */
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
    }

    /**
     * Reconstructs a DeviceQueueConfig from a persisted MongoDB document.
     *
     * @param doc MongoDB document previously produced by toDocument()
     * @throws CorruptedConfiguration If required fields are missing or invalid
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
    }

    // | ================= Serialisation ================= |

    /**
     * Serialises this config to a JSONObject for persistence in MongoDB.
     *
     * @return JSONObject ready to store in the deviceQueueConfig collection
     */
    public JSONObject toDocument() {
        JSONObject doc = new JSONObject();
        doc.put("gatewayDeviceId",      gatewayDeviceId);
        doc.put("retryIntervalSeconds", (int) retryInterval.getSeconds());
        doc.put("maxTimeToLiveSeconds", (int) maxTimeToLive.getSeconds());
        return doc;
    }

    // | ================= Getters ================= |

    public String   getGatewayDeviceId() { return gatewayDeviceId; }
    public Duration getRetryInterval()   { return retryInterval; }
    public Duration getMaxTimeToLive()   { return maxTimeToLive; }
}