package protocol_translation.exceptions;

public class CorruptedConfiguration extends Exception {
    CorruptedConfiguration(String message) {
        super("CorruptedConfiguration: " + message);
    }
}
