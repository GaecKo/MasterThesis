package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.auth.AuthenticationManager;
import edge_control.auth.AuthorizationManager;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.IllegalOperation;
import edge_control.exceptions.OperationNotSupported;
import edge_control.logger.EdgeControlLogger;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

        String authenticationcheckerResult = authenticationManager.checkAuthentication(request.getHeader("apikey"));
        if (authenticationcheckerResult.startsWith("Invalid API key")) {
            handleException(response, new IllegalOperation(authenticationcheckerResult));
            return;
        }

        logger.info("Authentication successful for API key: " + request.getHeader("apikey") + " | Result: " + authenticationcheckerResult);

        if(authorizationManager.checkAuthorization(authenticationcheckerResult, Document.parse(request.getBody()))){
            if (authenticationcheckerResult.startsWith("backend_")){
                request.setHeader("gatewayBackendId", authenticationcheckerResult);
                logger.info(authenticationcheckerResult+ " is authorized to perform the operation.");
            } else if (authenticationcheckerResult.startsWith("device_")){
                request.setHeader("gatewayDeviceId", authenticationcheckerResult);
                logger.info(authenticationcheckerResult+ " is authorized to perform the operation.");
            }
        } else {
            handleException(response, new IllegalOperation("Unauthorized access"));
            return;
        }

        // Continue APISIX chain
        chain.filter(request, response);
    }

    /**
     * Handles exceptions thrown during request processing.
     * Maps known exceptions to specific HTTP response codes and headers.
     *
     * @param response the HTTP response to populate
     * @param e the exception to handle
     */
    private void handleException(HttpResponse response, Exception e) {
        logger.error("Request failed: " + e);

        switch (e) {
            case CorruptedConfiguration corruptedConfiguration -> {
                response.setStatusCode(400);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
            case IllegalOperation illegalOperation -> {
                response.setStatusCode(403);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
            case OperationNotSupported operationNotSupported -> {
                response.setStatusCode(501);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
            default -> {
                response.setStatusCode(500);
                response.setHeader("X-Error", e.getMessage());
                response.setBody(e.getMessage());
            }
        }
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
