package protocol_translation.device.config;

import org.json.*;
import org.springframework.util.DigestUtils;


public class DeviceConfig {
    private final JSONObject config;
    private String deviceId;
    private String adapter;

    public DeviceConfig(String deviceId, String adapter, JSONObject config) {
        this.config = config;
        this.deviceId = deviceId;
        this.adapter = adapter;
        // TODO: retrieve adapter type and deviceId
    }

    public JSONObject getConfig() {
        return config;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getAdapter() {
        return adapter;
    }

    public String fingerprint() {
        return DigestUtils.md5DigestAsHex(config.toString().getBytes());

    }

}
