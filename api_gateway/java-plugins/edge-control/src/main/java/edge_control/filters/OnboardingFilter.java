package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.backend.BackendManager;
import edge_control.device.DeviceManager;
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

/**
 * APISIX plugin filter that handles protocol translation for devices.
 *
 * Responsibilities:
 * - Routes requests to the appropriate device adapters or management endpoints.
 * - Handles health checks and device management operations.
 * - Provides structured logging and error handling.
 * - Marks requests as processed and continues the APISIX filter chain.
 */
@Component
public class OnboardingFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(OnboardingFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private final BackendManager backendManager =
            BackendManager.getInstance();

    private final DeviceManager deviceManager =
            DeviceManager.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();

    /**
     * Initializes the plugin and logs startup messages.
     */
    OnboardingFilter() {
        logger.info("Onboarding Filter initialized");
        API_LOGGER.warn("Onboarding Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "Onboarding";
    }

    /**
     * Main filter method invoked by APISIX.
     * Routes requests to onboarding for adding a backend or a device, but also for configuring the authorizations.
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
        // register request
        requestHandler.register(request);

        // check if this filter should skip request
        if (requestHandler.shouldSkipRequest(request, chain)) {
            // logger.info(name() + " skips request...");
            chain.filter(request, response);
            return;
        }


//        logger.debug("Path: " + request.getPath());
//        logger.debug("Method: " + request.getMethod());
//        logger.debug("Source IP: " + request.getSourceIP());

        switch (request.getMethod()){
            case POST -> {
                try {
                    if (request.getPath().endsWith("/backend")) {
                        logger.debug("addBackend reached");
                        this.handleBackendCreation(request,response);

                    } else if (request.getPath().endsWith("/backendAuthZ")) {
                        logger.debug("authorization backend reached");
                        this.handleBackendAuthorizationConfig(request,response);

                    } else if (request.getPath().endsWith("/device")) {
                        logger.debug("addDevice reached");
                        this.handleDeviceCreation(request,response);

                    } else if (request.getPath().endsWith("/deviceAuthZ")) {
                        logger.debug("authorization device reached");
                        this.handleDeviceAuthorizationConfig(request,response);

                    }
                } catch (Exception e) {
                    this.handleException(response, e);
                }
            }
            default -> {
                logger.debug("Received unsupported HTTP method: " + request.getMethod());
            }
        }


        // Continue APISIX chain
        chain.filter(request, response);
    }

    /**
     * Handles the creation of a new backend based on the request body.
     *
     * @param request the incoming HTTP request containing the backend configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleBackendCreation(HttpRequest request, HttpResponse response) throws Exception {
        Document gatewayBackendInfo = backendManager.createBackend(request.getBody());
        response.setStatusCode(200);
        response.setBody(gatewayBackendInfo.toJson());
        requestHandler.skipChain(request);
    }

    /**
     * Handles the configuration of backend authorization based on the request body.
     *
     * @param request the incoming HTTP request containing the authorization configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleBackendAuthorizationConfig(HttpRequest request, HttpResponse response) throws Exception {
        Document resp = backendManager.addBackendAuthorizationConfig(request.getBody());
        response.setStatusCode(200);
        response.setBody(resp.toJson());
        requestHandler.skipChain(request);
    }

    /**
     * Handles the creation of a new device based on the request body.
     *
     * @param request the incoming HTTP request containing the device configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleDeviceCreation(HttpRequest request, HttpResponse response) throws Exception {
        Document gatewayDeviceInfo = deviceManager.createDevice(request.getBody());
        response.setStatusCode(200);
        response.setBody(gatewayDeviceInfo.toJson());
        requestHandler.skipChain(request);
    }

    /**
     * Handles the configuration of device authorization based on the request body.
     *
     * @param request the incoming HTTP request containing the authorization configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleDeviceAuthorizationConfig(HttpRequest request, HttpResponse response) throws Exception {
        Document resp = deviceManager.addDeviceAuthorizationConfig(request.getBody());
        response.setStatusCode(200);
        response.setBody(resp.toJson());
        requestHandler.skipChain(request);
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
