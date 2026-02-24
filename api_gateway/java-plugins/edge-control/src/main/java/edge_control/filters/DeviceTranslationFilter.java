package edge_control.filters;

import edge_control.RequestHandler;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import edge_control.device_translation.DeviceTranslationManager;
import edge_control.logger.EdgeControlLogger;
import edge_control.device_translation.adapter.DeviceAdapter;
import edge_control.exceptions.*;

import java.util.Arrays;

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
public class DeviceTranslationFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(DeviceTranslationFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private final DeviceTranslationManager deviceTranslationManager =
            DeviceTranslationManager.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();

    /**
     * Initializes the plugin and logs startup messages.
     */
    DeviceTranslationFilter() {
        logger.info("DeviceTranslation Filter initialized");
        API_LOGGER.warn("DeviceTranslation Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "DeviceTranslation";
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
        logger.debug("Incoming request in " + name() + ", index: " + chain.getIndex());
        // register request
        requestHandler.register(request);

        // check if this filter should skip request
        if (requestHandler.shouldSkipRequest(request, chain)) {
            // logger.info(name() + " skips request...");
            chain.filter(request, response);
            return;
        }

        try {

            if (request.getPath().startsWith("/health")) {
                logger.debug("Health endpoint reached");
                response.setStatusCode(200);
                response.setBody("Health Check reacted!\n");

            } else if (request.getPath().startsWith("/devices")) {
                handleDeviceManagementRequest(request, response);
            } else if (request.getPath().startsWith("/command")) {
                // Device traffic
                handleDeviceRequest(request, response);
            } else {
                throw new IllegalOperation("ProtocolTranslation filter is available for /devices and /command route.");
            }
        } catch (Exception e) {
            handleException(response, e);
            requestHandler.skipChain(request);
        }

        // Continue APISIX chain
        chain.filter(request, response);
    }

    private void handleDeviceManagementRequest(HttpRequest request, HttpResponse response) throws OperationNotSupported, CorruptedConfiguration, EdgeControlException {
        switch (request.getMethod()) {
            case PUT: {
                // create new Adapter and save config in db
                deviceTranslationManager.createAdapter(request.getBody());
                response.setStatusCode(200);
                response.setBody("Device Translation Created");
                requestHandler.skipChain(request);
                break;
            }
            default: {
                throw new OperationNotSupported("Method: " + request.getMethod() + " not supported on route /devices");
            }
        }
    }

    /**
     * Routes incoming requests to the corresponding device adapter.
     * Responds with errors if device ID is missing or unknown.
     *
     * @param request the incoming HTTP request
     * @param response the HTTP response to populate
     * @throws Exception if adapter processing fails
     */
    private void handleDeviceRequest(HttpRequest request,
                                     HttpResponse response) throws Exception {

        // TODO: move this to other class
        JSONObject config = new JSONObject(request.getBody());

        if (!config.has("gatewayDeviceId")) {
            logger.debug("No device ID...");
            response.setStatusCode(400);
            response.setHeader("X-Error", "Missing gatewayDeviceId in request body");
            response.setBody("X-Error: Missing gatewayDeviceId in body");
            return;
        }
        String gatewayDeviceId = config.getString("gatewayDeviceId");

        DeviceAdapter adapter = deviceTranslationManager.get(gatewayDeviceId);

        if (adapter == null) {
            response.setStatusCode(404);
            response.setHeader("X-Error", "Unknown device - no adapter linked: " + gatewayDeviceId);
            response.setBody("X-Error: Unknown device - no adapter linked: " + gatewayDeviceId);
            return;
        }

        // TODO: future request handling via adapter
        adapter.handleRequest(request, response);
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
        // logger.debug("Stack trace: " + Arrays.toString(e.getStackTrace()));

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
