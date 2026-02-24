package edge_control.exceptions;

public class OperationNotSupported extends Exception {
    public OperationNotSupported(String operation) {
        super("OperationNotSupported: " + operation);
    }
}
