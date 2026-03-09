package edge_control.exceptions;

public class IllegalOperation extends EdgeControlException {
    public IllegalOperation(String message) {
        super("IllegalOperation: " + message);
    }
}
