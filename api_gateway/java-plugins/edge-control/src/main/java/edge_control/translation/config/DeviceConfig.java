package edge_control.translation.config;

import edge_control.exceptions.CorruptedConfiguration;
import org.json.JSONObject;
import org.springframework.util.DigestUtils;

public class DeviceConfig {

    private JSONObject config;
    private String deviceId;
    private String adapter;

    public DeviceConfig(JSONObject config) throws CorruptedConfiguration {
        this.config = config;
        this.deviceId = config.getString("gatewayDeviceId");
        if (deviceId == null || deviceId.isBlank()) {
            throw new CorruptedConfiguration("Missing 'gatewayDeviceId' field for creating DeviceConfig");
        }
        this.adapter = config.getString("adapter");
        if (adapter == null || adapter.isBlank()) {
            throw new CorruptedConfiguration("Missing 'adapter' field for creating DeviceConfig");
        }
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
