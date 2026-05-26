package edge_control.translation.adapter.command.definition;

import edge_control.translation.adapter.command.engine.path.CompiledPath;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Contract for a parsed and compiled command definition.
 * Provides the payload template, field mappings, and cleanup policy
 * used by CommandTranslationEngine to produce a device-ready payload.
 */
public interface CommandDefinition {

    /** @return The command name as declared in the device config */
    String getName();

    /**
     * Returns a deep copy of the payload template.
     * A fresh copy is returned on each call so translations never share state.
     *
     * @return Mutable deep copy of the payload template
     */
    JsonNode createPayloadInstance();

    /**
     * Returns the pre-compiled field mappings from param name to target path.
     *
     * @return Unmodifiable map of param name to CompiledPath
     */
    Map<String, CompiledPath> getCompiledMappings();

    // | ================= Cleanup policy ================= |

    /** @return True if null fields/elements should be removed from the payload */
    boolean removeNulls();

    /** @return True if empty strings and empty arrays should be removed from the payload */
    boolean removeEmpty();

    /** @return True if empty objects should be converted to null before cleanup */
    boolean emptyObjectToNull();
}