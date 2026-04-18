package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.auth.AuthenticationManager;
import edge_control.auth.AuthorizationManager;
import edge_control.exceptions.ExceptionHandler;
import edge_control.exceptions.IllegalOperation;
import edge_control.logger.EdgeControlLogger;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.bson.Document;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * APISIX plugin filter that authenticates and authorises inbound requests.
 *
 * Validates the apikey header, then enriches the request body with identity
 * and routing info before passing it to downstream filters:
 * - Backend requests: authorization checked, callbackEndpoint injected
 * - Device requests: gatewayDeviceId and listOfEndpoints injected, no auth check needed
 */
@Component
public class AuthFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(AuthFilter.class);

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static final AuthenticationManager authenticationManager =
            AuthenticationManager.getInstance();

    private static final AuthorizationManager authorizationManager =
            AuthorizationManager.getInstance();

    private static final RequestHandler requestHandler = RequestHandler.getInstance();

    private static final DateTimeFormatter TIMING_FORMATTER =
            DateTimeFormatter.ofPattern("hh:mm:ss.SSSS").withZone(ZoneId.systemDefault());

    AuthFilter() {
        logger.info("Auth Filter initialized");
        API_LOGGER.warn("Auth Filter is running");
    }

    @Override
    public String name() {
        return "AuthFilter";
    }

    /**
     * Authenticates the request via its apikey header and enriches the body with
     * identity and routing fields for downstream filters to consume.
     * Only POST on /backendForward or /command is accepted.
     *
     * @param request  Inbound HTTP request
     * @param response HTTP response to populate on failure
     * @param chain    APISIX filter chain
     */
    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {

        int reqHash = request.hashCode();
        Instant start = Instant.now();
        logger.time("[" + TIMING_FORMATTER.format(start) + "] Auth Filter: request arrived (" + reqHash + ")");
        logger.debug("Incoming request in " + name() + ", index: " + chain.getIndex() + " | Path: " + request.getPath());

        requestHandler.register(request);

        // A previous filter may have marked this request to be skipped
        if (requestHandler.shouldSkipRequest(request, chain)) {
            chain.filter(request, response);
            return;
        }

        // This filter only handles POST — reject everything else early
        if (!request.getMethod().equals(HttpRequest.Method.POST)) {
            ExceptionHandler.handleException(response, new IllegalOperation("Only POST method is allowed"));
            requestHandler.skipChain(request);
            chain.filter(request, response);
            return;
        }

        // Only /backendForward (device telemetry) and /command (backend command) are valid
        if (!request.getPath().endsWith("/backendForward") && !request.getPath().endsWith("/command")) {
            ExceptionHandler.handleException(response, new IllegalOperation("Invalid endpoint"));
            requestHandler.skipChain(request);
            chain.filter(request, response);
            return;
        }

        // Verify the apikey and resolve the caller's gateway ID (backend_ or device_ prefixed)
        String gatewayId;
        try {
            gatewayId = authenticationManager.checkAuthentication(request.getHeader("apikey"));
        } catch (Exception e) {
            ExceptionHandler.handleException(response, new IllegalOperation(e.getMessage()));
            requestHandler.skipChain(request);
            chain.filter(request, response);
            return;
        }

        logger.info("Authentication successful for API key: " + request.getHeader("apikey") + " | Result: " + gatewayId);

        JSONObject body = new JSONObject(request.getBody());

        if (gatewayId.startsWith("backend_")) {
            // Backend requests require an explicit authorization check against the device
            if (authorizationManager.checkAuthorization(gatewayId, Document.parse(request.getBody()))) {
                // Inject callbackEndpoint so the queuing layer knows where to notify on retry
                String callback = authorizationManager.getCallbackEndpoint(gatewayId);
                if (callback != null) {
                    body.put("callbackEndpoint", callback);
                }
                request.setBody(body.toString());
                logger.info(gatewayId + " is authorized to perform the operation.");

                Instant end = Instant.now();
                logger.time("[" + TIMING_FORMATTER.format(end) + "] Auth Filter: request chained - time took:"
                        + (end.toEpochMilli() - start.toEpochMilli()) + "ms (" + reqHash + ")");
                chain.filter(request, response);
            } else {
                ExceptionHandler.handleException(response, new IllegalOperation("Unauthorized access"));
                requestHandler.skipChain(request);
                chain.filter(request, response);
            }

        } else if (gatewayId.startsWith("device_")) {
            // Device requests are forwarded to all registered backends — no per-device auth check needed
            body.put("gatewayDeviceId", gatewayId);

            // listOfEndpoints maps gatewayBackendId to endpoint URL, consumed by BackendForwarderFilter
            Map<String, String> endpoints = authorizationManager.getDeviceEndpoints(gatewayId);
            body.put("listOfEndpoints", new JSONObject(endpoints));

            request.setBody(body.toString());
            logger.info(gatewayId + " is authorized to perform the operation.");
            chain.filter(request, response);
        }
    }

    @Override
    public Boolean requiredBody() {
        return true;
    }
}