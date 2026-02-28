package edge_control.translation.adapter.command.engine.path;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

public final class CompiledPath {

    private final PathSegment[] segments;

    public CompiledPath(List<PathSegment> segments) {
        this.segments = segments.toArray(new PathSegment[0]);
    }

    public void apply(ObjectNode root, JsonNode value, ObjectMapper mapper) {

        JsonNode current = root;

        for (int i = 0; i < segments.length; i++) {

            PathSegment segment = segments[i];
            boolean isLast = (i == segments.length - 1);

            if (segment instanceof FieldSegment fieldSegment) {

                String field = fieldSegment.fieldName;

                if (isLast) {
                    ((ObjectNode) current).set(field, value);
                    return;
                }

                JsonNode next = current.get(field);

                // in case current field doesn't exist as is,
                // create array or object at that path
                if (next == null || next.isNull()) {

                    PathSegment nextSegment = segments[i + 1];

                    if (nextSegment instanceof ArrayIndexSegment) {
                        next = mapper.createArrayNode();
                    } else {
                        next = mapper.createObjectNode();
                    }

                    ((ObjectNode) current).set(field, next);
                }

                current = next;

            } else if (segment instanceof ArrayIndexSegment arraySegment) {

                int index = arraySegment.index;

                if (!(current instanceof ArrayNode array)) {
                    throw new IllegalStateException(
                            "Expected ArrayNode but found: " + current.getNodeType()
                    );
                }

                // in case index is higher than size of list
                while (array.size() <= index) {
                    array.add(mapper.createObjectNode());
                }

                if (isLast) {
                    array.set(index, value);
                    return;
                }

                current = array.get(index);
            }
        }
    }
}