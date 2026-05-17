package edge_control;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.translation.adapter.command.definition.MqttCommandDefinition;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class MqttCommandDefinitionTest {

    @Test
    void parsesValidConfigWithResponse() throws Exception {
        JSONObject commandJson = baseCommandJson()
                .put("responseTopic", "devices/device_1/telemetry")
                .put("timeouts", new JSONObject().put("response", 12))
                .put("cleanup", new JSONObject().put("removeNulls", true));

        MqttCommandDefinition definition = new MqttCommandDefinition("setPower", commandJson);

        assertThat(definition.getName()).isEqualTo("setPower");
        assertThat(definition.getTopic()).isEqualTo("devices/device_1/commands");
        assertThat(definition.getResponseTopic()).isEqualTo("devices/device_1/telemetry");
        assertThat(definition.hasResponseTopic()).isTrue();
        assertThat(definition.getResponseTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(definition.removeNulls()).isTrue();
        assertThat(definition.removeEmpty()).isFalse();
        assertThat(definition.emptyObjectToNull()).isFalse();

        JsonNode first = definition.createPayloadInstance();
        first.withObject("state").put("x", 1);
        JsonNode second = definition.createPayloadInstance();
        assertThat(second.has("state")).isTrue();
        assertThat(second.at("/state/x").isMissingNode()).isTrue();
    }

    @Test
    void responseTopicEmpty() throws Exception {
        JSONObject commandJson = baseCommandJson().put("responseTopic", "");

        MqttCommandDefinition definition = new MqttCommandDefinition("setPower", commandJson);

        assertThat(definition.hasResponseTopic()).isFalse();
        assertThat(definition.getResponseTopic()).isNull();
        assertThat(definition.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void missingTopicRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("topic");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'topic'");
    }

    @Test
    void invalidPayloadTemplateRejected() {
        JSONObject commandJson = baseCommandJson().put("payloadTemplate", "not-an-object");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("invalid 'payloadTemplate'");
    }

    @Test
    void invalidMappingPathRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.getJSONObject("mappings").put("power", "state[no]");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("has invalid path");
    }

    @Test
    void missingNameRejected() {
        JSONObject commandJson = baseCommandJson().put("name", "");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'name'");
    }

    @Test
    void emptyTopicRejected() {
        JSONObject commandJson = baseCommandJson().put("topic", "");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'topic'");
    }

    @Test
    void missingPayloadTemplateRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("payloadTemplate");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'payloadTemplate'");
    }

    @Test
    void missingMappingsRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("mappings");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'mappings'");
    }

    @Test
    void emptyMappingPathRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.getJSONObject("mappings").put("power", "");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("null or empty path");
    }

    @Test
    void defaultsAppliedWhenOptionalBlocksMissing() throws Exception {
        JSONObject commandJson = baseCommandJson();

        MqttCommandDefinition definition = new MqttCommandDefinition("cmd", commandJson);

        assertThat(definition.removeNulls()).isFalse();
        assertThat(definition.removeEmpty()).isFalse();
        assertThat(definition.emptyObjectToNull()).isFalse();
        assertThat(definition.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(definition.getResponseTopic()).isNull();
        assertThat(definition.hasResponseTopic()).isFalse();
    }

    @Test
    void compiledPathMissingReturnsNull() throws Exception {
        JSONObject commandJson = baseCommandJson();

        MqttCommandDefinition definition = new MqttCommandDefinition("cmd", commandJson);

        assertThat(definition.getCompiledPath("unknown")).isNull();
    }

    @Test
    void compiledMappingsUnmodifiable() throws Exception {
        JSONObject commandJson = baseCommandJson();

        MqttCommandDefinition definition = new MqttCommandDefinition("cmd", commandJson);

        assertThatThrownBy(() -> definition.getCompiledMappings().put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingNameFieldRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("name");

        assertThatThrownBy(() -> new MqttCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'name'");
    }

    @Test
    void timeoutsBlockWithoutResponseDefaultsToTenSeconds() throws Exception {
        JSONObject commandJson = baseCommandJson()
                .put("timeouts", new JSONObject());

        MqttCommandDefinition definition = new MqttCommandDefinition("cmd", commandJson);

        assertThat(definition.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void nonStringMappingValueCoercedToString() throws Exception {
        JSONObject commandJson = baseCommandJson();
        commandJson.getJSONObject("mappings").put("power", 123);

        MqttCommandDefinition definition = new MqttCommandDefinition("cmd", commandJson);

        assertThat(definition.getCompiledPath("power")).isNotNull();
    }

    @Test
    void emptyMappingsAllowed() throws Exception {
        JSONObject commandJson = baseCommandJson().put("mappings", new JSONObject());

        MqttCommandDefinition definition = new MqttCommandDefinition("cmd", commandJson);

        assertThat(definition.getCompiledMappings()).isEmpty();
    }

    @Test
    void cleanupFlagsParsedWhenPresent() throws Exception {
        JSONObject commandJson = baseCommandJson()
                .put("cleanup", new JSONObject()
                        .put("removeNulls", true)
                        .put("removeEmpty", true)
                        .put("emptyObjectToNull", true));

        MqttCommandDefinition definition = new MqttCommandDefinition("cmd", commandJson);

        assertThat(definition.removeNulls()).isTrue();
        assertThat(definition.removeEmpty()).isTrue();
        assertThat(definition.emptyObjectToNull()).isTrue();
    }

    private static JSONObject baseCommandJson() {
        return new JSONObject()
                .put("name", "setPower")
                .put("topic", "devices/device_1/commands")
                .put("payloadTemplate", new JSONObject().put("state", new JSONObject()))
                .put("mappings", new JSONObject()
                        .put("power", "state.power"));
    }
}

