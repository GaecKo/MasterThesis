package edge_control.exceptions;

/** Thrown when a device config is structurally invalid or missing required fields. */
public class CorruptedConfiguration extends EdgeControlException {
    public CorruptedConfiguration(String message) {
        super("CorruptedConfiguration: " + message);
    }
}