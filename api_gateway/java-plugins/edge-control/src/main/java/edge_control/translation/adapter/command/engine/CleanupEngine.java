package edge_control.translation.adapter.command.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies cleanup rules to a translated JSON payload.
 * Operates recursively and mutates the input node in-place.
 */
public class CleanupEngine {

    /**
     * Recursively cleans a JSON node according to the given policy flags.
     *
     * @param node              The node to clean (mutated in-place)
     * @param removeNulls       Remove fields/elements whose value is null
     * @param removeEmpty       Remove fields/elements whose value is an empty string or empty array
     * @param emptyObjectToNull Convert empty objects to null before applying removeNulls
     * @return The cleaned node, or null if the input was null
     */
    public static JsonNode clean(
            JsonNode node,
            boolean removeNulls,
            boolean removeEmpty,
            boolean emptyObjectToNull
    ) {
        if (node == null) return null;

        // Delegate to type-specific handlers since objects and arrays need structural cleanup
        if (node.isObject()) {
            return cleanObject((ObjectNode) node, removeNulls, removeEmpty, emptyObjectToNull);
        }
        if (node.isArray()) {
            return cleanArray((ArrayNode) node, removeNulls, removeEmpty, emptyObjectToNull);
        }
        // Primitives (strings, numbers, booleans) are leaf nodes — nothing to recurse into
        return node;
    }

    // | ================= Object cleaning ================= |

    /**
     * Cleans an object node by recursively cleaning its fields and removing
     * those that match the cleanup policy.
     * Fields are collected for removal after iteration to avoid concurrent modification.
     */
    private static JsonNode cleanObject(
            ObjectNode objectNode,
            boolean removeNulls,
            boolean removeEmpty,
            boolean emptyObjectToNull
    ) {
        // Collect fields to remove after iterating — modifying the object during iteration
        // would throw a ConcurrentModificationException
        List<String> fieldsToRemove = new ArrayList<>();

        for (Map.Entry<String, JsonNode> entry : objectNode.properties()) {
            String field = entry.getKey();
            JsonNode value = entry.getValue();

            // Recurse into nested objects and arrays before evaluating this field
            JsonNode cleanedValue = clean(value, removeNulls, removeEmpty, emptyObjectToNull);

            // emptyObjectToNull runs before removeNulls so that empty objects can be caught
            // by the null removal step in the same pass
            if (emptyObjectToNull && cleanedValue.isObject() && cleanedValue.isEmpty()) {
                cleanedValue = NullNode.getInstance();
            }

            // Write the cleaned value back regardless — it may have had nested fields removed
            objectNode.set(field, cleanedValue);

            if (removeNulls && cleanedValue.isNull()) {
                fieldsToRemove.add(field);
                continue;
            }

            if (removeEmpty) {
                if (cleanedValue.isString() && cleanedValue.asString().isEmpty()) {
                    fieldsToRemove.add(field);
                    continue;
                }
                // An array that had all its elements removed counts as empty
                if (cleanedValue.isArray() && cleanedValue.isEmpty()) {
                    fieldsToRemove.add(field);
                }
            }
        }

        fieldsToRemove.forEach(objectNode::remove);

        return objectNode;
    }

    // | ================= Array cleaning ================= |

    /**
     * Cleans an array node by recursively cleaning its elements and removing
     * those that match the cleanup policy.
     * Iterates backwards so index-based removals do not shift remaining elements.
     */
    private static JsonNode cleanArray(
            ArrayNode arrayNode,
            boolean removeNulls,
            boolean removeEmpty,
            boolean emptyObjectToNull
    ) {
        // Backwards iteration is required — removing index i shifts all elements above it
        // down by one, which would cause elements to be skipped if iterating forwards
        for (int i = arrayNode.size() - 1; i >= 0; i--) {
            JsonNode value = arrayNode.get(i);

            // Recurse before applying this element's removal rules
            JsonNode cleanedValue = clean(value, removeNulls, removeEmpty, emptyObjectToNull);

            if (emptyObjectToNull && cleanedValue.isObject() && cleanedValue.isEmpty()) {
                cleanedValue = NullNode.getInstance();
            }

            // Write the cleaned element back before deciding whether to remove it
            arrayNode.set(i, cleanedValue);

            if (removeNulls && cleanedValue.isNull()) {
                arrayNode.remove(i);
                continue;
            }

            if (removeEmpty) {
                if (cleanedValue.isString() && cleanedValue.asString().isEmpty()) {
                    arrayNode.remove(i);
                    continue;
                }
                // Nested empty arrays are also subject to removal
                if (cleanedValue.isArray() && cleanedValue.isEmpty()) {
                    arrayNode.remove(i);
                }
            }
        }

        return arrayNode;
    }
}