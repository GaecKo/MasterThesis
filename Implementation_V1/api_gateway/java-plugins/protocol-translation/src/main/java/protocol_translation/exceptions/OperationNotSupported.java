package protocol_translation.exceptions;

public class OperationNotSupported extends Exception {
    public OperationNotSupported(String operation) {
        super("OperationNotSupported: " + operation + " is not supported.");
    }
}
