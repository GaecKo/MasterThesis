package edge_control.exceptions;

/**
 * Thrown by a DeviceAdapter when the target device cannot be reached.
 * Signals to the caller that the request should be queued for retry.
 */
public class DeviceUnreachableException extends EdgeControlException {
    public DeviceUnreachableException(String message) {
        super(message);
    }
}