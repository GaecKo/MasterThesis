package edge_control;

import edge_control.translation.adapter.command.engine.path.CompiledPath;
import edge_control.translation.adapter.command.engine.path.PathCompiler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

class PathCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void applyWritesIntoNestedArrayAndObject() {
        ObjectNode root = MAPPER.createObjectNode();
        CompiledPath path = PathCompiler.compile("schedule[0].operation.activePower");

        path.apply(root, MAPPER.getNodeFactory().numberNode(5), MAPPER);

        assertThat(root.at("/schedule/0/operation/activePower").asInt()).isEqualTo(5);
    }

    @Test
    void applyPadsArraysWhenIndexOutOfBounds() {
        ObjectNode root = MAPPER.createObjectNode();
        CompiledPath path = PathCompiler.compile("items[2].value");

        path.apply(root, MAPPER.getNodeFactory().textNode("ok"), MAPPER);

        assertThat(root.get("items").size()).isEqualTo(3);
        assertThat(root.at("/items/2/value").asText()).isEqualTo("ok");
    }

    @Test
    void applyThrowsWhenRootIsNotArrayForArraySegment() {
        ObjectNode root = MAPPER.createObjectNode();
        CompiledPath path = PathCompiler.compile("[0].value");

        assertThatThrownBy(() -> path.apply(root, MAPPER.getNodeFactory().textNode("x"), MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected ArrayNode");
    }
}


