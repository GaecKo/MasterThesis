package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.translation.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import edge_control.translation.DeviceTranslationManager;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.exceptions.*;

import java.util.Arrays;

/**
 * APISIX plugin filter that handles protocol translation for devices.
 *
 * IMPORTANT: This filter NEVER blocks the event loop thread.
 * All slow operations are handled asynchronously with callbacks.
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
        logger.info("Available processors: " + Runtime.getRuntime().availableProcessors());
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

        try {
            if (request.getPath().startsWith("/health")) {
                // Fast path - handle synchronously
                handleHealthCheck(request, response, chain);

            } else if (request.getPath().startsWith("/devices")) {
                // Fast path - handle synchronously
                handleDeviceManagementRequest(request, response, chain);

            } else if (request.getPath().startsWith("/command")) {
                // SLOW PATH - handle asynchronously with NO BLOCKING
                // We pass the chain to the async handler which will call chain.filter() when done
                handleDeviceRequestAsync(request, response, chain);
                // IMPORTANT: Return immediately WITHOUT calling chain.filter()
                // The async handler will call it later

            } else {
                throw new IllegalOperation("ProtocolTranslation filter is available for /devices and /command route.");
            }
        } catch (Exception e) {
            // Synchronous error handling
            handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
        }
    }

    /**
     * Fast path - health check.
     */
    private void handleHealthCheck(HttpRequest request,
                                   HttpResponse response,
                                   PluginFilterChain chain) {
        logger.debug("Health endpoint reached");
        response.setStatusCode(200);
        response.setBody("Health Check reacted!\n");
        chain.filter(request, response);
    }

    /**
     * Fast path - device management.
     */
    private void handleDeviceManagementRequest(HttpRequest request,
                                               HttpResponse response,
                                               PluginFilterChain chain)
            throws OperationNotSupported, CorruptedConfiguration, EdgeControlException {

        switch (request.getMethod()) {
            case POST: {
                deviceTranslationManager.createAdapter(request.getBody());
                response.setStatusCode(200);
                response.setBody("Device Translation Created");
                chain.filter(request, response);
                break;
            }
            case DELETE: {
                if (deviceTranslationManager.deleteDeviceConfig(request.getBody())) {
                    response.setStatusCode(200);
                    response.setBody("Device Translation Config Deleted - success");
                    chain.filter(request, response);
                } else {
                    response.setStatusCode(404);
                    response.setHeader("X-Error", "Unknown device - no config to delete");
                    response.setBody("X-Error: Unknown device - no config to delete");
                    chain.filter(request, response);
                }
                break;
            }
            case GET: {
                DeviceConfig deviceConfig = deviceTranslationManager.getConfig(request.getBody());
                if (deviceConfig == null) {
                    response.setStatusCode(404);
                    response.setHeader("X-Error", "Unknown device - no config to retrieve");
                    response.setBody("X-Error: Unknown device - no config to retrieve");
                    chain.filter(request, response);
                } else {
                    response.setStatusCode(200);
                    response.setBody(deviceConfig.toString()); // to string removes _id automatically
                    chain.filter(request, response);
                }
                break;
            }
            default: {
                throw new OperationNotSupported("Method: " + request.getMethod() + " not supported on route /devices");
            }
        }
    }

    /**
     * SLOW PATH - Fully asynchronous, non-blocking device request handling.
     * This method returns immediately and the callback continues the chain.
     * Routes incoming requests to the corresponding device adapter.
     * Responds with errors if device ID is missing or unknown.
     *
     * @param request the incoming HTTP request
     * @param response the HTTP response to populate
     * @throws Exception if adapter processing fails
     */
    private void handleDeviceRequestAsync(HttpRequest request,
                                          HttpResponse response,
                                          PluginFilterChain chain) {

        try {
            // Parse request body (fast operation)
            JSONObject config = new JSONObject(request.getBody());

            if (!config.has("gatewayDeviceId")) {
                // Fast error path - handle synchronously
                logger.debug("No device ID...");
                response.setStatusCode(400);
                response.setHeader("X-Error", "Missing gatewayDeviceId in request body");
                response.setBody("X-Error: Missing gatewayDeviceId in body");
                chain.filter(request, response);
                return;
            }

            String gatewayDeviceId = config.getString("gatewayDeviceId");
            DeviceAdapter adapter = deviceTranslationManager.get(gatewayDeviceId);

            if (adapter == null) {
                // Fast error path - handle synchronously
                response.setStatusCode(404);
                response.setHeader("X-Error", "Unknown device - no adapter linked: " + gatewayDeviceId);
                response.setBody("X-Error: Unknown device - no adapter linked: " + gatewayDeviceId);
                requestHandler.skipChain(request);
                chain.filter(request, response);
                return;
            }

            // Delegate to adapter - it will make async HTTP call and call the callback when done
            // We pass a callback that continues the filter chain
            adapter.handleRequest(request, response, () -> {
                // This callback is executed after the async HTTP call completes
                try {
                    // Continue the filter chain now that response is modified
                    chain.filter(request, response);
                } catch (Exception e) {
                    logger.error("Error in chain.filter callback: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            // Handle any synchronous errors in the setup phase
            logger.error("Error in handleDeviceRequestAsync setup: " + e.getMessage());
            handleException(response, e);
            chain.filter(request, response);
        }
        // Method returns immediately - NO WAITING, NO BLOCKING
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
        logger.debug("Stack trace: " + Arrays.toString(e.getStackTrace()));


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
