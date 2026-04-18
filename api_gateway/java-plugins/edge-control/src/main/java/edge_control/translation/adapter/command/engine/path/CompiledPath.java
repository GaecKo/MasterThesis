package edge_control.translation.adapter.command.engine.path;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * A pre-compiled sequence of path segments representing a dot-notation or
 * array-indexed path into a JSON object (e.g. "schedule[0].operation.activePower").
 *
 * Compiled once at config load time and reused across all requests for efficiency.
 */
public final class CompiledPath {

    private final PathSegment[] segments;

    /**
     * @param segments Ordered list of path segments produced by PathCompiler
     */
    public CompiledPath(List<PathSegment> segments) {
        this.segments = segments.toArray(new PathSegment[0]);
    }

    /**
     * Writes the given value into the root object at the location described by this path.
     * Intermediate nodes that are missing or null are created automatically.
     * Intermediate arrays are padded with empty objects if the target index is out of bounds.
     *
     * @param root   Root ObjectNode to write into (mutated in-place)
     * @param value  Value to write at the path destination
     * @param mapper ObjectMapper used to create intermediate nodes
     * @throws IllegalStateException If an ArrayIndexSegment is encountered but the current node is not an array
     */
    public void apply(ObjectNode root, JsonNode value, ObjectMapper mapper) {

        JsonNode current = root;

        for (int i = 0; i < segments.length; i++) {

            PathSegment segment = segments[i];
            boolean isLast = (i == segments.length - 1);

            if (segment instanceof FieldSegment fieldSegment) {

                String field = fieldSegment.fieldName;

                if (isLast) {
                    // Destination reached — write the value and stop
                    ((ObjectNode) current).set(field, value);
                    return;
                }

                JsonNode next = current.get(field);

                // If the intermediate node is absent or null, create it based on
                // what the next segment expects: array index -> ArrayNode, field -> ObjectNode
                if (next == null || next.isNull()) {
                    PathSegment nextSegment = segments[i + 1];
                    next = (nextSegment instanceof ArrayIndexSegment)
                            ? mapper.createArrayNode()
                            : mapper.createObjectNode();
                    ((ObjectNode) current).set(field, next);
                }

                current = next;

            } else if (segment instanceof ArrayIndexSegment arraySegment) {

                int index = arraySegment.index;

                if (!(current instanceof ArrayNode array)) {
                    throw new IllegalStateException(
                            "Expected ArrayNode but found: " + current.getNodeType());
                }

                // Pad with empty objects until the array is large enough to hold the target index
                while (array.size() <= index) {
                    array.add(mapper.createObjectNode());
                }

                if (isLast) {
                    // Destination reached — write the value and stop
                    array.set(index, value);
                    return;
                }

                current = array.get(index);
            }
        }
    }
}