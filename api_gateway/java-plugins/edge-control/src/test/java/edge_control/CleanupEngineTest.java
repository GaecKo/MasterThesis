package edge_control;

import edge_control.translation.adapter.command.engine.CleanupEngine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class CleanupEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void removeNullsOnly() throws Exception {
        JsonNode input = MAPPER.readTree("{" +
                "\"a\":null," +
                "\"b\":\"\"," +
                "\"c\":[]," +
                "\"d\":{\"e\":null}," +
                "\"f\":[null,\"x\"]," +
                "\"g\":{}" +
                "}");

        JsonNode cleaned = CleanupEngine.clean(input, true, false, false);

        assertThat(cleaned.has("a")).isFalse();
        assertThat(cleaned.get("b").asText()).isEmpty();
        assertThat(cleaned.get("c").isArray()).isTrue();
        assertThat(cleaned.get("d").isObject()).isTrue();
        assertThat(cleaned.get("d").size()).isZero();
        assertThat(cleaned.has("e")).isFalse();
        assertThat(cleaned.get("f").size()).isEqualTo(1);
        assertThat(cleaned.get("f").get(0).asText()).isEqualTo("x");
        assertThat(cleaned.get("g").isObject()).isTrue();
    }

    @Test
    void removeEmptyAndEmptyObjectToNull() throws Exception {
        JsonNode input = MAPPER.readTree("{" +
                "\"a\":null," +
                "\"b\":\"\"," +
                "\"c\":[]," +
                "\"d\":{\"e\":{}}," +
                "\"f\":[\"\",[],{}]" +
                "}");

        JsonNode cleaned = CleanupEngine.clean(input, true, true, true);

        assertThat(cleaned.has("a")).isFalse();
        assertThat(cleaned.has("b")).isFalse();
        assertThat(cleaned.has("c")).isFalse();
        assertThat(cleaned.has("d")).isFalse();
        assertThat(cleaned.has("f")).isFalse();
    }
}
