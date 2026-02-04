package edge_control.exceptions;

public class IllegalOperation extends Exception {
    IllegalOperation(String message) {
        super("IllegalOperation: " + message);
    }
}
