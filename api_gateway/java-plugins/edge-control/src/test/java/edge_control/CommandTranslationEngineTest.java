package edge_control;

import edge_control.translation.adapter.command.definition.CommandDefinition;
import edge_control.translation.adapter.command.definition.HttpCommandDefinition;
import edge_control.translation.adapter.command.engine.CommandTranslationEngine;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

class CommandTranslationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void translatesParamsAndAppliesCleanup() throws Exception {
        CommandDefinition definition = new HttpCommandDefinition("cmd",
                new JSONObject()
                        .put("name", "cmd")
                        .put("endpoint", "http://example.com")
                        .put("method", "POST")
                        .put("payloadTemplate", new JSONObject()
                                .put("payload", new JSONObject()
                                        .put("activePower", 0)
                                        .put("note", ""))
                                .put("unchanged", 1))
                        .put("mappings", new JSONObject()
                                .put("activePower", "payload.activePower")
                                .put("note", "payload.note"))
                        .put("cleanup", new JSONObject()
                                .put("removeNulls", true)
                                .put("removeEmpty", true)
                                .put("emptyObjectToNull", false))
        );

        JsonNode backendRequest = MAPPER.readTree("{\"params\":{\"activePower\":5,\"note\":\"\"}}" );

        JsonNode result = new CommandTranslationEngine().translate(definition, backendRequest);

        assertThat(result.at("/payload/activePower").asInt()).isEqualTo(5);
        assertThat(result.at("/payload/note").isMissingNode()).isTrue();
        assertThat(result.at("/unchanged").asInt()).isEqualTo(1);
    }

    @Test
    void missingParamsRejected() throws Exception {
        CommandDefinition definition = new HttpCommandDefinition("cmd",
                new JSONObject()
                        .put("name", "cmd")
                        .put("endpoint", "http://example.com")
                        .put("method", "POST")
                        .put("payloadTemplate", new JSONObject())
                        .put("mappings", new JSONObject())
        );

        ObjectNode backendRequest = MAPPER.createObjectNode();

        assertThatThrownBy(() -> new CommandTranslationEngine().translate(definition, backendRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing or invalid 'params'");
    }

    @Test
    void unmappedParamsDoNotOverwriteTemplate() throws Exception {
        CommandDefinition definition = new HttpCommandDefinition("cmd",
                new JSONObject()
                        .put("name", "cmd")
                        .put("endpoint", "http://example.com")
                        .put("method", "POST")
                        .put("payloadTemplate", new JSONObject()
                                .put("payload", new JSONObject().put("activePower", 1)))
                        .put("mappings", new JSONObject()
                                .put("activePower", "payload.activePower"))
        );

        JsonNode backendRequest = MAPPER.readTree("{\"params\":{}}" );

        JsonNode result = new CommandTranslationEngine().translate(definition, backendRequest);

        assertThat(result.at("/payload/activePower").asInt()).isEqualTo(1);
    }

    @Test
    void translatesComplexPayloadTemplate() throws Exception {
        CommandDefinition definition = new HttpCommandDefinition("cmd",
                new JSONObject()
                        .put("name", "cmd")
                        .put("endpoint", "http://example.com")
                        .put("method", "POST")
                        .put("payloadTemplate", new JSONObject()
                                .put("schedule", new org.json.JSONArray()
                                        .put(new JSONObject()
                                                .put("operation", new JSONObject()
                                                        .put("activePower", 0)
                                                        .put("reactivePower", 0))
                                                .put("window", new JSONObject()
                                                        .put("startAt", "")
                                                        .put("endAt", ""))))
                                .put("metadata", new JSONObject()
                                        .put("assetId", "")
                                        .put("tags", new org.json.JSONArray())))
                        .put("mappings", new JSONObject()
                                .put("activePower", "schedule[0].operation.activePower")
                                .put("reactivePower", "schedule[0].operation.reactivePower")
                                .put("startAt", "schedule[0].window.startAt")
                                .put("endAt", "schedule[0].window.endAt")
                                .put("assetId", "metadata.assetId"))
                        .put("cleanup", new JSONObject()
                                .put("removeNulls", true)
                                .put("removeEmpty", true)
                                .put("emptyObjectToNull", false))
        );

        JsonNode backendRequest = MAPPER.readTree("{" +
                "\"params\":{\"activePower\":5000,\"reactivePower\":1000," +
                "\"startAt\":\"2025-01-01T00:00:00Z\",\"endAt\":\"2025-01-02T00:00:00Z\",\"assetId\":\"asset_42\"}" +
                "}");

        JsonNode result = new CommandTranslationEngine().translate(definition, backendRequest);

        assertThat(result.at("/schedule/0/operation/activePower").asInt()).isEqualTo(5000);
        assertThat(result.at("/schedule/0/operation/reactivePower").asInt()).isEqualTo(1000);
        assertThat(result.at("/schedule/0/window/startAt").asText()).isEqualTo("2025-01-01T00:00:00Z");
        assertThat(result.at("/schedule/0/window/endAt").asText()).isEqualTo("2025-01-02T00:00:00Z");
        assertThat(result.at("/metadata/assetId").asText()).isEqualTo("asset_42");
        assertThat(result.has("metadata")).isTrue();
    }
}
