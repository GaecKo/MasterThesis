package edge_control.translation.config;

import edge_control.exceptions.CorruptedConfiguration;
import org.json.JSONObject;
import org.springframework.util.DigestUtils;

/**
 * Parsed representation of a device's translation configuration.
 * Holds the raw JSON config alongside the extracted deviceId and adapter type.
 */
public class DeviceConfig {

    private JSONObject config;
    private String deviceId;
    private String adapter;

    /**
     * Parses and validates a device config JSON object.
     *
     * @param config Raw JSON config document from the database
     * @throws CorruptedConfiguration If 'gatewayDeviceId' or 'adapter' is missing or blank
     */
    public DeviceConfig(JSONObject config) throws CorruptedConfiguration {
        this.config = config;

        // Use optString so we get null on missing fields rather than a JSONException
        this.deviceId = config.optString("gatewayDeviceId", null);
        if (deviceId == null || deviceId.isBlank()) {
            throw new CorruptedConfiguration("Missing 'gatewayDeviceId' field for creating DeviceConfig");
        }

        this.adapter = config.optString("adapter", null);
        if (adapter == null || adapter.isBlank()) {
            throw new CorruptedConfiguration("Missing 'adapter' field for creating DeviceConfig");
        }
    }

    /**
     * Computes an MD5 fingerprint of the config, excluding the '_id' field.
     * Used to detect configuration changes without comparing full documents.
     *
     * @return Hex-encoded MD5 hash of the config without '_id'
     */
    public String fingerprint() {
        // Temporarily remove '_id' since it changes on every DB write but is not part of the config
        Object id = config.remove("_id");
        String fingerprint = DigestUtils.md5DigestAsHex(config.toString().getBytes());
        config.put("_id", id);
        return fingerprint;
    }

    /** @return The raw JSON config document */
    public JSONObject getConfig()  { return config; }

    /** @return The gateway device ID */
    public String getDeviceId()    { return deviceId; }

    /** @return The adapter type string (e.g. "http", "mqtt") */
    public String getAdapter()     { return adapter; }

    /**
     * Returns a pretty-printed JSON string of the config, excluding '_id'.
     */
    @Override
    public String toString() {
        Object id = config.remove("_id");
        String configString = config.toString(4);
        config.put("_id", id);
        return configString;
    }
}