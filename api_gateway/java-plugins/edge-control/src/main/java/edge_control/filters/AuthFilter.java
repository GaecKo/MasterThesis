package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.auth.AuthenticationManager;
import edge_control.auth.AuthorizationManager;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.ExceptionHandler;
import edge_control.exceptions.IllegalOperation;
import edge_control.exceptions.OperationNotSupported;
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

import java.util.Map;

@Component
public class AuthFilter implements PluginFilter {
    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(AuthFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private final AuthenticationManager authenticationManager =
            AuthenticationManager.getInstance();

    private final AuthorizationManager authorizationManager =
            AuthorizationManager.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();

    /**
     * Initializes the plugin and logs startup messages.
     */
    AuthFilter() {
        logger.info("Auth Filter initialized");
        API_LOGGER.warn("Auth Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "AuthFilter";
    }

    /**
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

        // notice about reached filter / route
        logger.debug("Incoming request in " + name() + ", index: " + chain.getIndex() + " | Path: " + request.getPath());

        // register request
        requestHandler.register(request);

        // check if this filter should skip request
        if (requestHandler.shouldSkipRequest(request, chain)) {
            // logger.info(name() + " skips request...");
            chain.filter(request, response);
            return;
        }

        // Named authenticationcheckerResult instead of gatewayId because it can be either backend_ or device_ ID, but also the result of the authentication
        // check (e.g. "Invalid API key or API key cannot be null") in case of failed authentication
        String authenticationcheckerResult = authenticationManager.checkAuthentication(request.getHeader("apikey"));
        if (authenticationcheckerResult.startsWith("Invalid API key")) {
            ExceptionHandler.handleException(response, new IllegalOperation(authenticationcheckerResult));
            requestHandler.skipChain(request);
            chain.filter(request, response);
            return;
        }

        logger.info("Authentication successful for API key: " + request.getHeader("apikey") + " | Result: " + authenticationcheckerResult);

        JSONObject body = new JSONObject(request.getBody());
        if (authenticationcheckerResult.startsWith("backend_")) {
            // Backend requests require authorization check
            if (authorizationManager.checkAuthorization(authenticationcheckerResult, Document.parse(request.getBody()))) {
                body.put("gatewayBackendId", authenticationcheckerResult);
                request.setBody(body.toString());
                logger.info(authenticationcheckerResult + " is authorized to perform the operation.");
            } else {
                ExceptionHandler.handleException(response, new IllegalOperation("Unauthorized access"));
                requestHandler.skipChain(request);
                chain.filter(request, response);
                return;
            }
        } else if (authenticationcheckerResult.startsWith("device_")) {
            // Device requests are automatically forwarded to all available endpoints — no authorization check needed
            body.put("gatewayDeviceId", authenticationcheckerResult);

            // Add listOfEndpoints: { gatewayBackendId → endpoint }
            Map<String, String> endpoints = authorizationManager.getDeviceEndpoints(authenticationcheckerResult);
            JSONObject listOfEndpoints = new JSONObject(endpoints);
            body.put("listOfEndpoints", listOfEndpoints);

            request.setBody(body.toString());
            logger.info(authenticationcheckerResult + " is authorized to perform the operation.");
            logger.info(body.toString());
        }

        // Continue APISIX chain
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
