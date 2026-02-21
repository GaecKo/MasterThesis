package edge_control.filters;

import edge_control.RequestHandler;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import edge_control.device.DeviceMappingManager;
import edge_control.logger.EdgeControlLogger;
import edge_control.device.adapter.DeviceAdapter;
import edge_control.exceptions.*;

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
public class ProtocolTranslationFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(ProtocolTranslationFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private final DeviceMappingManager deviceMappingManager =
            DeviceMappingManager.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();

    /**
     * Initializes the plugin and logs startup messages.
     */
    ProtocolTranslationFilter() {
        logger.info("ProtocolTranslation Filter initialized");
        API_LOGGER.warn("ProtocolTranslation Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "ProtocolTranslation";
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


//        logger.debug("Path: " + request.getPath());
//        logger.debug("Method: " + request.getMethod());
//        logger.debug("Source IP: " + request.getSourceIP());

        try {

            if (request.getPath().startsWith("/health")) {
                logger.debug("Health endpoint reached");
                response.setStatusCode(200);
                response.setBody("Health Check reacted!\n");

            } else {
                // Device traffic
                handleDeviceRequest(request, response);

            }
        } catch (Exception e) {
            handleException(response, e);
        }

        // Continue APISIX chain
        chain.filter(request, response);
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

        // TODO: move this to deviceManager
        String deviceId = request.getHeader("X-Device-Id");

        if (deviceId == null || deviceId.isBlank()) {
            logger.debug("No device ID...");
            response.setStatusCode(400);
            response.setHeader("X-Error", "Missing X-Device-Id header");
            response.setBody("X-Error: Missing X-Device-Id header");
            return;
        }

        DeviceAdapter adapter = deviceMappingManager.get(deviceId);

        if (adapter == null) {
            response.setStatusCode(404);
            response.setHeader("X-Error", "Unknown device: " + deviceId);
            response.setBody("X-Error: Unknown device: ");
            return;
        }

        // TODO: future request handling via adapter
        logger.debug("Request routed to device" + deviceId);
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
