package edge_control;

import edge_control.logger.EdgeControlLogger;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class RequestHandler {

    private static RequestHandler INSTANCE;
    private static ConcurrentHashMap<HttpRequest, Boolean> skipRequest;

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();


    public static RequestHandler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RequestHandler();
        }
        return INSTANCE;
    }

    private RequestHandler() {
        skipRequest = new ConcurrentHashMap<>();
    }

    public void register(HttpRequest httpRequest) {
        skipRequest.putIfAbsent(httpRequest, false);
    }

    public boolean shouldSkipRequest(HttpRequest httpRequest, PluginFilterChain chain) {
        boolean cont = skipRequest.get(httpRequest);


        // Filters: StartFilter -> Filter 1 -> Filter 2 -> Filter 3         -> EndFilter
        // Index:             0 ->        1 ->        2 ->        3         -> 4
        // Action:               |  1st reg  |  check    | check + delete
        if (chain.getIndex() >= chain.getFilters().size() - 1) {
            // logger.info("Removing request at filter with index: " + chain.getIndex());
            // remove request from our hashmap if it won't be used anymore
            skipRequest.remove(httpRequest);
        }

        return cont;
    }

    public void skipChain(HttpRequest httpRequest) {
        skipRequest.put(httpRequest, true);
    }


}
