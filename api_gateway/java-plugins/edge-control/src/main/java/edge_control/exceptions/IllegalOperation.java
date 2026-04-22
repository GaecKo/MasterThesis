package edge_control.exceptions;

/** Thrown when a request references an unknown command or an unsupported operation. */
public class IllegalOperation extends EdgeControlException {
    public IllegalOperation(String message) {
        super("IllegalOperation: " + message);
    }
}