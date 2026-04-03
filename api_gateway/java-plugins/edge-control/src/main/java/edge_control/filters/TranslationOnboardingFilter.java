package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.exceptions.*;
import edge_control.logger.EdgeControlLogger;
import edge_control.translation.DeviceTranslationManager;
import edge_control.translation.config.DeviceConfig;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
public class TranslationOnboardingFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(TranslationOnboardingFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private final DeviceTranslationManager deviceTranslationManager =
            DeviceTranslationManager.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();


    /**
     * Initializes the plugin and logs startup messages.
     */
    TranslationOnboardingFilter() {
        logger.info("TranslationOnboarding Filter initialized");
        API_LOGGER.warn("TranslationOnboarding Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "TranslationOnboarding";
    }

    /**
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
            if (request.getPath().startsWith("/onboarding/translation")) {

                handleDeviceManagementRequest(request, response, chain);

            } else {
                throw new IllegalOperation("TranslationOnboarding filter is available for /onboarding/translation route.");
            }
        } catch (Exception e) {
            // Synchronous error handling
            ExceptionHandler.handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
        }
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
     * Indicates that the plugin requires the request body to function.
     *
     * @return true
     */
    @Override
    public Boolean requiredBody() {
        return true;
    }
}
