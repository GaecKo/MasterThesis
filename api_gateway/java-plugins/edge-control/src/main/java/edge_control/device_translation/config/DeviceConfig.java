package edge_control.device_translation.config;

import org.json.JSONObject;
import org.springframework.util.DigestUtils;

public class DeviceConfig {

    private JSONObject config;
    private String deviceId;
    private String adapter;

    public DeviceConfig(JSONObject config) {
        this.config = config;
        this.deviceId = config.getString("gatewayDeviceId");
        this.adapter = config.getString("adapter");
    }

    public String fingerprint() {
        Object o = config.remove("_id");
        String fingerprint = DigestUtils.md5DigestAsHex(config.toString().getBytes());
        config.put("_id", o);
        return fingerprint;
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

    @Override
    public String toString() {
        return config.toString();
    }
}
