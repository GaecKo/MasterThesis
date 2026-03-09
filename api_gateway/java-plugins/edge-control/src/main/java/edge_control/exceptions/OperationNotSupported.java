package edge_control.exceptions;

public class OperationNotSupported extends EdgeControlException {
    public OperationNotSupported(String operation) {
        super("OperationNotSupported: " + operation);
    }
}
