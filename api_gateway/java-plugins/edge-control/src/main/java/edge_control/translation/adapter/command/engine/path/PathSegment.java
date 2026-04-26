package edge_control.translation.adapter.command.engine.path;

/**
 * Base type for a single segment in a compiled path.
 * Either a field name (FieldSegment) or an array index (ArrayIndexSegment).
 */
abstract class PathSegment {}

/**
 * Represents a named field access in a path, e.g. "operation" in "operation.activePower".
 */
final class FieldSegment extends PathSegment {

    /** The field name to access on the current ObjectNode. */
    final String fieldName;

    FieldSegment(String fieldName) {
        this.fieldName = fieldName;
    }
}

/**
 * Represents an array index access in a path, e.g. "[0]" in "schedule[0].startAt".
 */
final class ArrayIndexSegment extends PathSegment {

    /** The zero-based index to access on the current ArrayNode. */
    final int index;

    ArrayIndexSegment(int index) {
        this.index = index;
    }
}