package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.exceptions.ExceptionHandler;
import edge_control.exceptions.IllegalOperation;
import edge_control.logger.EdgeControlLogger;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * APISIX plugin filter that forwards an inbound device message to all registered backend endpoints.
 *
 * The list of endpoints is injected into the request body by the AuthFilter as 'listOfEndpoints'.
 * All forwards are fired in parallel and the filter returns 202 immediately without waiting for results.
 */
@Component
public class BackendForwarderFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(BackendForwarderFilter.class);

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static final RequestHandler requestHandler = RequestHandler.getInstance();

    BackendForwarderFilter() {
        logger.info("BackendForwarder Filter initialized");
        API_LOGGER.warn("BackendForwarder Filter is running");
    }

    @Override
    public String name() {
        return "BackendForwarder";
    }

    /**
     * Extracts the list of backend endpoints from the request body and forwards the message
     * to all of them in parallel. Returns 202 immediately without waiting for backend responses.
     * Must never block the Netty event loop thread.
     *
     * @param request  Inbound request; body must contain 'listOfEndpoints'
     * @param response HTTP response populated with 202 before returning
     * @param chain    APISIX filter chain
     */
    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {

        logger.debug("Incoming request in " + name() + ", index: " + chain.getIndex());

        requestHandler.register(request);

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

            // Strip internal routing fields before forwarding to backends
            requestJson.remove("listOfEndpoints");
            requestJson.remove("gatewayBackendId");

            endpoints.forEach((key, value) -> backendEndpoints.add((String) value));
            forwardBody = requestJson.toString();

        } catch (Exception e) {
            ExceptionHandler.handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
            return;
        }

        // Fire all forwards in parallel — each backend is independent, failures are logged only
        List<CompletableFuture<Void>> forwards = backendEndpoints.stream()
                .map(endpoint -> HttpForgery.doRequestAsync(
                                "POST",
                                endpoint,
                                forwardBody,
                                request.getHeaders(),
                                Duration.of(10, ChronoUnit.SECONDS),
                                Duration.of(30, ChronoUnit.SECONDS))
                        .thenAccept(result -> logger.debug(
                                "Forwarded to " + endpoint + " - status=" + result.statusCode()))
                        .exceptionally(throwable -> {
                            Throwable cause = throwable.getCause() != null
                                    ? throwable.getCause() : throwable;
                            logger.error("Forward to " + endpoint + " failed: " + cause.getMessage());
                            return null;
                        }))
                .toList();

        // Log completion of all forwards for observability, but do not block the response on them
        CompletableFuture.allOf(forwards.toArray(new CompletableFuture[0]))
                .thenRun(() -> logger.debug("All forwards completed for request in " + name()));

        // Return 202 immediately — the device does not need to wait for backend acknowledgements
        response.setStatusCode(202);
        response.setBody("{\"status\":\"Forwarded\"}");
        response.setHeader("MODIFIED-BY", "EdgeControl/Backend-Forwarder");
        requestHandler.skipChain(request);
        chain.filter(request, response);
    }

    @Override
    public Boolean requiredBody() {
        return true;
    }
}