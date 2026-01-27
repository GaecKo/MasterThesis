package protocol_translation.device_adapter;

import org.json.*;


public class DeviceConfig {
    private final JSONObject config;
    private String deviceId;
    private String adapter;

    public DeviceConfig(JSONObject config) {
        this.config = config;
        // TODO: retrieve adapter type and deviceId
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getAdapter() {
        return adapter;
    }
}
