package edge_control.translation.adapter.command.engine.path;

import java.util.ArrayList;
import java.util.List;

public final class PathCompiler {

    private PathCompiler() {}

    public static CompiledPath compile(String path) {

        List<PathSegment> segments = new ArrayList<>();

        int i = 0;
        StringBuilder buffer = new StringBuilder();

        while (i < path.length()) {

            char c = path.charAt(i);

            if (c == '.') {
                if (buffer.length() > 0) {
                    segments.add(new FieldSegment(buffer.toString()));
                    buffer.setLength(0);
                }
                i++;
                continue;
            }

            if (c == '[') {
                // flush field name first
                if (buffer.length() > 0) {
                    segments.add(new FieldSegment(buffer.toString()));
                    buffer.setLength(0);
                }

                int end = path.indexOf(']', i);
                int index = Integer.parseInt(path.substring(i + 1, end));

                segments.add(new ArrayIndexSegment(index));
                i = end + 1;
                continue;
            }

            buffer.append(c);
            i++;
        }

        if (buffer.length() > 0) {
            segments.add(new FieldSegment(buffer.toString()));
        }

        return new CompiledPath(segments);
    }
}
