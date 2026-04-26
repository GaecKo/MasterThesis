package edge_control;

import edge_control.logger.EdgeControlLogger;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-request state across the APISIX filter chain.
 *
 * Each request is registered on first encounter and marked with a skip flag
 * if an earlier filter decides the chain should not proceed (e.g. auth failure).
 * The entry is cleaned up automatically at the last filter in the chain.
 */
public class RequestHandler {

    private static RequestHandler instance;

    // Tracks whether each in-flight request should be skipped by subsequent filters
    private final ConcurrentHashMap<HttpRequest, Boolean> skipRequest = new ConcurrentHashMap<>();

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private RequestHandler() {}

    /**
     * Returns the singleton instance, creating it on first call.
     */
    public static synchronized RequestHandler getInstance() {
        if (instance == null) {
            instance = new RequestHandler();
        }
        return instance;
    }

    // | ================= Request lifecycle ================= |

    /**
     * Registers a request when it first enters the filter chain.
     * If the request is already registered, this is a no-op.
     *
     * @param httpRequest The incoming request to register
     */
    public void register(HttpRequest httpRequest) {
        skipRequest.putIfAbsent(httpRequest, false);
    }

    /**
     * Returns whether this request has been marked for skipping by a previous filter.
     * Also cleans up the entry when the last filter in the chain is reached.
     *
     * Filter chain example:
     *   StartFilter -> Filter1 -> Filter2 -> Filter3 -> EndFilter          -> RespFilter
     *   Index:              0  ->       1 ->       2 ->        3 -> 4
     *   Action:          register    check      check      check + remove
     *
     * @param httpRequest The request to check
     * @param chain       The current filter chain, used to detect the last filter
     * @return True if subsequent filters should skip processing this request
     */
    public boolean shouldSkipRequest(HttpRequest httpRequest, PluginFilterChain chain) {
        boolean skip = skipRequest.get(httpRequest);

        // Clean up when we reach the last filter so entries don't accumulate
        if (chain.getIndex() >= chain.getFilters().size() - 1) {
            skipRequest.remove(httpRequest);
        }

        return skip;
    }

    /**
     * Marks a request so that all subsequent filters in the chain will skip it.
     * Called when a filter handles the response itself and the chain should not continue normally.
     *
     * @param httpRequest The request to mark for skipping
     */
    public void skipChain(HttpRequest httpRequest) {
        skipRequest.put(httpRequest, true);
    }
}