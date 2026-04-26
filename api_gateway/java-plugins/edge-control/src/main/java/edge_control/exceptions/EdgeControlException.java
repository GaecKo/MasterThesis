package edge_control.exceptions;

/** Base checked exception for all EdgeControl domain errors. */
public class EdgeControlException extends Exception {
    public EdgeControlException(String message) {
        super(message);
    }
}