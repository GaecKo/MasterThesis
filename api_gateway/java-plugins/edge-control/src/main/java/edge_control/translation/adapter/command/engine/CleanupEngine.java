package edge_control.translation.adapter.command.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CleanupEngine {

    public static JsonNode clean(
            JsonNode node,
            boolean removeNulls,
            boolean removeEmpty,
            boolean emptyObjectToNull
    ) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            return cleanObject((ObjectNode) node, removeNulls, removeEmpty, emptyObjectToNull);
        }
        if (node.isArray()) {
            return cleanArray((ArrayNode) node, removeNulls, removeEmpty, emptyObjectToNull);
        }
        return node;
    }

    private static JsonNode cleanObject(
            ObjectNode objectNode,
            boolean removeNulls,
            boolean removeEmpty,
            boolean emptyObjectToNull
    ) {
        List<String> fieldsToRemove = new ArrayList<>();

        for (Map.Entry<String, JsonNode> entry : objectNode.properties()) {
            String field = entry.getKey();
            JsonNode value = entry.getValue();

            JsonNode cleanedValue = clean(value, removeNulls, removeEmpty, emptyObjectToNull);

            if (emptyObjectToNull && cleanedValue.isObject() && cleanedValue.isEmpty()) {
                cleanedValue = NullNode.getInstance();
            }

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
                if (cleanedValue.isArray() && cleanedValue.isEmpty()) {
                    fieldsToRemove.add(field);
                    continue;
                }
            }
        }

        for (String field : fieldsToRemove) {
            objectNode.remove(field);
        }

        return objectNode;
    }

    private static JsonNode cleanArray(
            ArrayNode arrayNode,
            boolean removeNulls,
            boolean removeEmpty,
            boolean emptyObjectToNull
    ) {
        // Iterate backwards so index removals don't shift remaining elements
        for (int i = arrayNode.size() - 1; i >= 0; i--) {
            JsonNode value = arrayNode.get(i);

            JsonNode cleanedValue = clean(value, removeNulls, removeEmpty, emptyObjectToNull);

            if (emptyObjectToNull && cleanedValue.isObject() && cleanedValue.isEmpty()) {
                cleanedValue = NullNode.getInstance();
            }

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
                if (cleanedValue.isArray() && cleanedValue.isEmpty()) {
                    arrayNode.remove(i);
                    continue;
                }
            }
        }

        return arrayNode;
    }
}