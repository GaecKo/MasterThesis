package edge_control;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.translation.config.DeviceConfig;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DeviceConfigTest {

    @Test
    void parsesValidConfig() throws Exception {
        JSONObject configJson = baseConfig()
                .put("adapter", "http")
                .put("gatewayDeviceId", "device_1");

        DeviceConfig config = new DeviceConfig(configJson);

        assertThat(config.getAdapter()).isEqualTo("http");
        assertThat(config.getDeviceId()).isEqualTo("device_1");
        assertThat(config.getConfig()).isSameAs(configJson);
    }

    @Test
    void missingGatewayDeviceIdRejected() {
        JSONObject configJson = baseConfig();
        configJson.remove("gatewayDeviceId");

        assertThatThrownBy(() -> new DeviceConfig(configJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("Missing 'gatewayDeviceId'");
    }

    @Test
    void missingAdapterRejected() {
        JSONObject configJson = baseConfig();
        configJson.remove("adapter");

        assertThatThrownBy(() -> new DeviceConfig(configJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("Missing 'adapter'");
    }

    @Test
    void fingerprintIgnoresIdChanges() throws Exception {
        JSONObject configJson = baseConfig()
                .put("adapter", "mqtt")
                .put("gatewayDeviceId", "device_2")
                .put("_id", "abc123");

        DeviceConfig config = new DeviceConfig(configJson);
        String first = config.fingerprint();

        configJson.put("_id", "def456");
        String second = config.fingerprint();

        assertThat(first).isEqualTo(second);
        assertThat(configJson.getString("_id")).isEqualTo("def456");
    }

    @Test
    void toStringOmitsId() throws Exception {
        JSONObject configJson = baseConfig()
                .put("adapter", "http")
                .put("gatewayDeviceId", "device_3")
                .put("_id", "id_123");

        DeviceConfig config = new DeviceConfig(configJson);

        assertThat(config.toString()).doesNotContain("\"_id\"");
        assertThat(configJson.getString("_id")).isEqualTo("id_123");
    }

    private static JSONObject baseConfig() {
        return new JSONObject()
                .put("adapter", "http")
                .put("gatewayDeviceId", "device_1")
                .put("meta", new JSONObject().put("x", 1));
    }
}

