package edge_control.exceptions;

public class CorruptedConfiguration extends Exception {
    public CorruptedConfiguration(String message) {
        super("CorruptedConfiguration: " + message);
    }
}
