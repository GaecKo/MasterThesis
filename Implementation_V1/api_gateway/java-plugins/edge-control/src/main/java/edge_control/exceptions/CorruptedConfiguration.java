package edge_control.exceptions;

public class CorruptedConfiguration extends Exception {
    CorruptedConfiguration(String message) {
        super("CorruptedConfiguration: " + message);
    }
}
