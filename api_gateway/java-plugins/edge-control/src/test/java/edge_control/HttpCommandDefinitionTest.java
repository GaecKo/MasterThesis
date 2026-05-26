package edge_control;

import edge_control.exceptions.CorruptedConfiguration;
import edge_control.translation.adapter.command.definition.HttpCommandDefinition;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class HttpCommandDefinitionTest {

    @Test
    void parsesValidConfig() throws Exception {
        JSONObject commandJson = baseCommandJson()
                .put("endpoint", "http://example.com/api")
                .put("method", "POST")
                .put("cleanup", new JSONObject()
                        .put("removeNulls", true)
                        .put("removeEmpty", true)
                        .put("emptyObjectToNull", true))
                .put("timeouts", new JSONObject()
                        .put("connect", 3)
                        .put("request", 7));

        HttpCommandDefinition definition = new HttpCommandDefinition("setPower", commandJson);

        assertThat(definition.getName()).isEqualTo("setPower");
        assertThat(definition.getEndpoint()).isEqualTo("http://example.com/api");
        assertThat(definition.getMethod()).isEqualTo("POST");
        assertThat(definition.removeNulls()).isTrue();
        assertThat(definition.removeEmpty()).isTrue();
        assertThat(definition.emptyObjectToNull()).isTrue();
        assertThat(definition.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(definition.getRequestTimeout()).isEqualTo(Duration.ofSeconds(7));

        JsonNode first = definition.createPayloadInstance();
        first.withObject("schedule").put("x", 1);
        JsonNode second = definition.createPayloadInstance();
        assertThat(second.has("schedule")).isTrue();
        assertThat(second.at("/schedule/x").isMissingNode()).isTrue();
    }

    @Test
    void missingNameRejected() {
        JSONObject commandJson = baseCommandJson().put("name", "");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'name'");
    }

    @Test
    void invalidEndpointRejected() {
        JSONObject commandJson = baseCommandJson().put("endpoint", "not a url");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("invalid endpoint");
    }

    @Test
    void invalidMethodRejected() {
        JSONObject commandJson = baseCommandJson().put("method", "FETCH");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("invalid method");
    }

    @Test
    void missingPayloadTemplateRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("payloadTemplate");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'payloadTemplate'");
    }

    @Test
    void invalidPayloadTemplateRejected() {
        JSONObject commandJson = baseCommandJson().put("payloadTemplate", "not-an-object");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("invalid 'payloadTemplate'");
    }

    @Test
    void missingMappingsRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("mappings");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'mappings'");
    }

    @Test
    void invalidMappingPathRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.getJSONObject("mappings").put("activePower", "schedule[abc]");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("has invalid path");
    }

    @Test
    void missingEndpointRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("endpoint");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'endpoint'");
    }

    @Test
    void emptyEndpointRejected() {
        JSONObject commandJson = baseCommandJson().put("endpoint", "");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'endpoint'");
    }

    @Test
    void missingMethodRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.remove("method");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'method'");
    }

    @Test
    void emptyMethodRejected() {
        JSONObject commandJson = baseCommandJson().put("method", "");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("misses 'method'");
    }

    @Test
    void emptyMappingPathRejected() {
        JSONObject commandJson = baseCommandJson();
        commandJson.getJSONObject("mappings").put("activePower", "");

        assertThatThrownBy(() -> new HttpCommandDefinition("cmd", commandJson))
                .isInstanceOf(CorruptedConfiguration.class)
                .hasMessageContaining("null or empty path");
    }

    @Test
    void defaultsAppliedWhenOptionalBlocksMissing() throws Exception {
        JSONObject commandJson = baseCommandJson();

        HttpCommandDefinition definition = new HttpCommandDefinition("cmd", commandJson);

        assertThat(definition.removeNulls()).isFalse();
        assertThat(definition.removeEmpty()).isFalse();
        assertThat(definition.emptyObjectToNull()).isFalse();
        assertThat(definition.getConnectTimeout()).isEqualTo(Duration.ofSeconds(0));
        assertThat(definition.getRequestTimeout()).isEqualTo(Duration.ofSeconds(0));
    }

    @Test
    void compiledPathMissingReturnsNull() throws Exception {
        JSONObject commandJson = baseCommandJson();

        HttpCommandDefinition definition = new HttpCommandDefinition("cmd", commandJson);

        assertThat(definition.getCompiledPath("unknown")).isNull();
    }

    @Test
    void compiledMappingsUnmodifiable() throws Exception {
        JSONObject commandJson = baseCommandJson();

        HttpCommandDefinition definition = new HttpCommandDefinition("cmd", commandJson);

        assertThatThrownBy(() -> definition.getCompiledMappings().put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static JSONObject baseCommandJson() {
        return new JSONObject()
                .put("name", "setPower")
                .put("endpoint", "http://example.com")
                .put("method", "POST")
                .put("payloadTemplate", new JSONObject().put("schedule", new JSONObject()))
                .put("mappings", new JSONObject()
                        .put("activePower", "schedule[0].operation.activePower"));
    }
}

