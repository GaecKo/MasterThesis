package protocol_translation.exceptions;

public class IllegalOperation extends Exception {
    IllegalOperation(String message) {
        super("IllegalOperation: " + message);
    }
}
