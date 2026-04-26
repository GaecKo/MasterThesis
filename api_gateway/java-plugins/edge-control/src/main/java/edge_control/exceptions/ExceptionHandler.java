package edge_control.exceptions;

import edge_control.logger.EdgeControlLogger;
import org.apache.apisix.plugin.runner.HttpResponse;

import java.util.Arrays;

/**
 * Maps exceptions to HTTP responses for use across all filters.
 * Known domain exceptions produce specific 4xx codes; all others produce 500.
 */
public class ExceptionHandler {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    /**
     * Populates the HTTP response based on the exception type.
     *
     * @param response HTTP response to populate
     * @param e        Exception to handle
     */
    public static void handleException(HttpResponse response, Exception e) {
        logger.error("Request failed: " + e);

        switch (e) {
            case CorruptedConfiguration cc -> {
                // Malformed or incomplete config — client error
                response.setStatusCode(400);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
            case IllegalOperation io -> {
                // Unknown command or invalid operation — forbidden
                response.setStatusCode(403);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
            case OperationNotSupported ons -> {
                // HTTP method not supported on the route
                response.setStatusCode(501);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
            default -> {
                // Unexpected error — log the full stack trace for debugging
                logger.debug("Stack trace: " + Arrays.toString(e.getStackTrace()));
                response.setStatusCode(500);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
        }
    }
}