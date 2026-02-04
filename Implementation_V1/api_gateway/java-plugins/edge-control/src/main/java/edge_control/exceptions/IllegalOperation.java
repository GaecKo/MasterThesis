package edge_control.exceptions;

public class IllegalOperation extends Exception {
    public IllegalOperation(String message) {
        super("IllegalOperation: " + message);
    }
}
