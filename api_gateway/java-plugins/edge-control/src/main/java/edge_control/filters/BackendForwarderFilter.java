package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.auth.tokens.GatewayTokensRegistry;
import edge_control.auth.tokens.TokenEntry;
import edge_control.exceptions.EdgeControlException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * APISIX plugin filter that forwards an inbound device message to all registered backend endpoints.
 *
 * The list of endpoints is injected into the request body by the AuthFilter as 'listOfEndpoints'.
 * All forwards are fired in parallel and the filter returns 202 immediately without waiting for results.
 *
 * If a token is registered in GatewayTokensRegistry for a backend (keyed by its backendId),
 * it is injected as an Authorization Bearer header on that backend's forwarded request.
 * Each backend uses its own token independently.
 */
@Component
public class BackendForwarderFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(BackendForwarderFilter.class);

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static final RequestHandler requestHandler = RequestHandler.getInstance();

    // Token registry — singleton, initialised once at class load.
    private static final GatewayTokensRegistry TOKEN_REGISTRY;
    static {
        GatewayTokensRegistry registry = null;
        try {
            registry = GatewayTokensRegistry.getInstance();
        } catch (EdgeControlException e) {
            logger.error("Failed to initialise GatewayTokensRegistry: " + e.getMessage()
                    + " — backend forwards will be made without credentials.");
        }
        TOKEN_REGISTRY = registry;
    }

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
     * The listOfEndpoints map is backendId -> url. Each backend's token is looked up
     * individually so different backends can use different credentials.
     *
     * @param request  Inbound request; body must contain 'listOfEndpoints' and 'gatewayBackendId'
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

        // Map of backendId -> endpoint URL
        Map<String, String> backendEndpoints = new HashMap<>();
        String forwardBody;

        try {
            JSONObject requestJson = new JSONObject(request.getBody());

            if (!requestJson.has("listOfEndpoints")) {
                throw new IllegalOperation("Missing listOfEndpoints in body: internal filter error");
            }

            requestJson.getJSONObject("listOfEndpoints")
                    .toMap()
                    .forEach((backendId, url) -> backendEndpoints.put(backendId, (String) url));

            // Strip internal routing fields before forwarding to backends
            requestJson.remove("listOfEndpoints");
            requestJson.remove("gatewayBackendId");

            forwardBody = requestJson.toString();

        } catch (Exception e) {
            ExceptionHandler.handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
            return;
        }

        // Fire all forwards in parallel — each backend gets its own token-injected headers
        List<CompletableFuture<Void>> forwards = backendEndpoints.entrySet().stream()
                .map(entry -> {
                    String backendId = entry.getKey();
                    String endpoint  = entry.getValue();

                    // Build per-backend headers, inject token if one is registered
                    Map<String, String> headers = new HashMap<>(request.getHeaders());
                    if (TOKEN_REGISTRY != null) {
                        TokenEntry tokenEntry = null;
                        try {
                            tokenEntry = TOKEN_REGISTRY.getDecryptedTokenByGatewayId(backendId);
                        } catch (EdgeControlException e) {
                            logger.error("BackendForwarderFilter could not get token for backend id " + backendId);
                        }
                        if (tokenEntry != null) {
                            headers.put("Authorization", "Bearer " + tokenEntry.decryptedToken());
                            logger.debug("Access token injected for backend " + backendId);
                        } else {
                            logger.debug("No access token for backend "
                                    + backendId + " — forwarding without credentials");
                        }
                    } else {
                        logger.warn("Token registry unavailable — forwarding backend "
                                + backendId + " without credentials");
                    }

                    return HttpForgery.doRequestAsync(
                                    "POST",
                                    endpoint,
                                    forwardBody,
                                    headers,
                                    Duration.of(10, ChronoUnit.SECONDS),
                                    Duration.of(30, ChronoUnit.SECONDS))
                            .thenAccept(result -> logger.debug(
                                    "Forwarded to " + endpoint + " - status=" + result.statusCode()))
                            .exceptionally(throwable -> {
                                Throwable cause = throwable.getCause() != null
                                        ? throwable.getCause() : throwable;
                                logger.error("Forward to " + endpoint + " failed: "
                                        + cause.getMessage());
                                return null;
                            });
                })
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