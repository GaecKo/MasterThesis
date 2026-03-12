package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.exceptions.ExceptionHandler;
import edge_control.exceptions.IllegalOperation;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.DeviceTranslationManager;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.translation.adapter.HttpForgery;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * APISIX plugin filter that handles protocol translation for devices.
 *
 * Responsibilities:
 * - forward a received message to multiple backends
 */
@Component
public class BackendForwarderFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(BackendForwarderFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();


    /**
     * Initializes the plugin and logs startup messages.
     */
    BackendForwarderFilter() {
        logger.info("BackendForwarder Filter initialized");
        API_LOGGER.warn("BackendForwarder Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "BackendForwarder";
    }

    /**
     * Main filter method invoked by APISIX on the event loop thread.
     * NEVER BLOCK THIS THREAD - return immediately for slow paths.
     * Main filter method invoked by APISIX.
     * Routes requests to health check, device management, or device adapters.
     *
     * @param request the incoming HTTP request
     * @param response the HTTP response to populate
     * @param chain the APISIX plugin filter chain
     */
    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {

        logger.debug("Incoming request in " + name() + ", index: " + chain.getIndex());

        // Register request
        requestHandler.register(request);

        // Check if this filter should skip request
        if (requestHandler.shouldSkipRequest(request, chain)) {
            chain.filter(request, response);
            return;
        }

        List<String> backendEndpoints = new ArrayList<>();
        String forwardBody;

        try {
            JSONObject requestJson = new JSONObject(request.getBody());
            if (!requestJson.has("listOfEndpoints")) {
                throw new IllegalOperation("Missing listOfEndpoints in body: internal filter error");
            }
            Map<String, Object> endpoints = requestJson.getJSONObject("listOfEndpoints").toMap();
            requestJson.remove("listOfEndpoints");
            endpoints.forEach((key, value) -> backendEndpoints.add((String) value));
            forwardBody = requestJson.toString();

        } catch (Exception e) {
            ExceptionHandler.handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
            return;
        }

        // Fire all forwards in parallel — do not block, do not wait for results
        List<CompletableFuture<Void>> forwards = backendEndpoints.stream()
                .map(endpoint -> HttpForgery.doRequestAsync(
                                "POST",
                                endpoint,
                                forwardBody,
                                request.getHeaders(),
                                Duration.of(10, ChronoUnit.SECONDS),
                                Duration.of(30, ChronoUnit.SECONDS))
                        .thenAccept(result -> {
                            logger.debug("Forwarded to " + endpoint
                                    + " — status=" + result.statusCode());
                        })
                        .exceptionally(throwable -> {
                            Throwable cause = throwable.getCause() != null
                                    ? throwable.getCause() : throwable;
                            logger.error("Forward to " + endpoint
                                    + " failed: " + cause.getMessage());
                            return null;
                        }))
                .toList();

        // Log when all complete, but do not block the response on them
        CompletableFuture.allOf(forwards.toArray(new CompletableFuture[0]))
                .thenRun(() -> logger.debug("All forwards completed for request in " + name()));

        // Return 202 immediately — device does not need to wait for backend responses
        response.setStatusCode(202);
        response.setBody("{\"status\":\"Forwarded\"}");
        response.setHeader("MODIFIED-BY", "EdgeControl/Backend-Forwarder");
        requestHandler.skipChain(request);
        chain.filter(request, response);
    }


    /**
     * Indicates that the plugin requires the request body to function.
     *
     * @return true
     */
    @Override
    public Boolean requiredBody() {
        return true;
    }

    /**
     * Indicates that the plugin requires the response body to function.
     *
     * @return true
     */
    @Override
    public Boolean requiredRespBody() {
        return true;
    }
}
