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

import edge_control.translation.DeviceTranslationManager;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.exceptions.*;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    // Thread pool for handling slow operations
    private static final ExecutorService executorService =
            Executors.newFixedThreadPool(20); // Increased from 2 to handle more concurrent requests

    /**
     * Initializes the plugin and logs startup messages.
     */
    DeviceTranslationFilter() {
        logger.info("DeviceTranslation Filter initialized");
        API_LOGGER.warn("DeviceTranslation Filter is running");
        logger.info("Available threads: " + Runtime.getRuntime().availableProcessors());
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
                logger.debug("Health endpoint reached");
                response.setStatusCode(200);
                response.setBody("Health Check reacted!\n");
                chain.filter(request, response);

            } else if (request.getPath().startsWith("/devices")) {
                // Fast path - handle synchronously
                handleDeviceManagementRequest(request, response);
                chain.filter(request, response);

            } else if (request.getPath().startsWith("/command")) {
                // SLOW PATH - handle asynchronously but wait for completion
                handleDeviceRequestAsync(request, response, chain);
                // Note: chain.filter() will be called inside handleDeviceRequestAsync after completion

            } else {
                throw new IllegalOperation("ProtocolTranslation filter is available for /devices and /command route.");
            }
        } catch (Exception e) {
            handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
        }
    }

    /**
     * Handles device requests asynchronously but waits for completion.
     * This allows other requests to be processed concurrently while waiting.
     */
    private void handleDeviceRequestAsync(HttpRequest request,
                                          HttpResponse response,
                                          PluginFilterChain chain) {

        // Create a CompletableFuture to track completion
        CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        // Submit the device request handling to the thread pool
        executorService.submit(() -> {
            try {
                // Parse request body
                JSONObject config = new JSONObject(request.getBody());

                if (!config.has("gatewayDeviceId")) {
                    logger.debug("No device ID...");
                    response.setStatusCode(400);
                    response.setHeader("X-Error", "Missing gatewayDeviceId in request body");
                    response.setBody("X-Error: Missing gatewayDeviceId in body");
                    completionFuture.complete(null);
                    return;
                }

                String gatewayDeviceId = config.getString("gatewayDeviceId");
                DeviceAdapter adapter = deviceTranslationManager.get(gatewayDeviceId);

                if (adapter == null) {
                    response.setStatusCode(404);
                    response.setHeader("X-Error", "Unknown device - no adapter linked: " + gatewayDeviceId);
                    response.setBody("X-Error: Unknown device - no adapter linked: " + gatewayDeviceId);
                    completionFuture.complete(null);
                    return;
                }

                // Delegate to adapter which will make async HTTP call
                // The adapter will complete the completionFuture when done
                adapter.handleRequest(request, response, completionFuture);

            } catch (Exception e) {
                handleException(response, e);
                completionFuture.complete(null);
            }
        });

        // Wait for the async operation to complete (with timeout)
        try {
            // Wait for the async operation to complete
            completionFuture.get();
        } catch (Exception e) {
            logger.error("Error waiting for async completion: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            response.setHeader("X-Error", e.getMessage());
        } finally {
            // Always continue the chain after response is ready
            chain.filter(request, response);
        }
    }

    private void handleDeviceManagementRequest(HttpRequest request, HttpResponse response) throws OperationNotSupported, CorruptedConfiguration, EdgeControlException {
        switch (request.getMethod()) {
            case POST: {
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
     * Handles exceptions thrown during request processing.
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

    @Override
    public Boolean requiredBody() {
        return true;
    }

    @Override
    public Boolean requiredRespBody() {
        return true;
    }
}