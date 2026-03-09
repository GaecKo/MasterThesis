package edge_control.exceptions;

public class CorruptedConfiguration extends EdgeControlException {
    public CorruptedConfiguration(String message) {
        super("CorruptedConfiguration: " + message);
    }
}
