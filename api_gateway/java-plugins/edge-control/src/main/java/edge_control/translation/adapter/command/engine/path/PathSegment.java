package edge_control.translation.adapter.command.engine.path;

abstract class PathSegment {
}

final class FieldSegment extends PathSegment {

    final String fieldName;

    FieldSegment(String fieldName) {
        this.fieldName = fieldName;
    }
}

final class ArrayIndexSegment extends PathSegment {

    final int index;

    ArrayIndexSegment(int index) {
        this.index = index;
    }
}