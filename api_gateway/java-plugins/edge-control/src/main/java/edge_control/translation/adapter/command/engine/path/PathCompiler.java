package edge_control.translation.adapter.command.engine.path;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a dot-notation path string into a reusable CompiledPath.
 * Supports field access (e.g. "operation.activePower") and array indexing (e.g. "schedule[0].startAt").
 */
public final class PathCompiler {

    // Utility class, not meant to be instantiated
    private PathCompiler() {}

    /**
     * Parses a path string into an ordered sequence of PathSegments.
     * Each segment is either a FieldSegment (object key) or an ArrayIndexSegment (array index).
     *
     * @param path Dot-notation path string, e.g. "schedule[0].operation.dispatchPower.activePower"
     * @return CompiledPath ready for use in CommandTranslationEngine
     * @throws NumberFormatException If an array index is not a valid integer
     */
    public static CompiledPath compile(String path) {

        List<PathSegment> segments = new ArrayList<>();

        int i = 0;
        // Buffer accumulates characters for the current field name token
        StringBuilder buffer = new StringBuilder();

        while (i < path.length()) {

            char c = path.charAt(i);

            if (c == '.') {
                // Dot delimiter — flush the accumulated field name and advance
                if (buffer.length() > 0) {
                    segments.add(new FieldSegment(buffer.toString()));
                    buffer.setLength(0);
                }
                i++;
                continue;
            }

            if (c == '[') {
                // Array index — flush any preceding field name first (e.g. "schedule" in "schedule[0]")
                if (buffer.length() > 0) {
                    segments.add(new FieldSegment(buffer.toString()));
                    buffer.setLength(0);
                }

                // Extract the index between '[' and ']'
                int end = path.indexOf(']', i);
                int index = Integer.parseInt(path.substring(i + 1, end));

                segments.add(new ArrayIndexSegment(index));
                // Skip past the closing bracket
                i = end + 1;
                continue;
            }

            // Regular character — accumulate into the current field name token
            buffer.append(c);
            i++;
        }

        // Flush any remaining field name that wasn't followed by a delimiter
        if (buffer.length() > 0) {
            segments.add(new FieldSegment(buffer.toString()));
        }

        return new CompiledPath(segments);
    }
}