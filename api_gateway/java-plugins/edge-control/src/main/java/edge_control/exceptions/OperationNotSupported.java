package edge_control.exceptions;

/** Thrown when an HTTP method is not supported on a given route. */
public class OperationNotSupported extends EdgeControlException {
    public OperationNotSupported(String operation) {
        super("OperationNotSupported: " + operation);
    }
}