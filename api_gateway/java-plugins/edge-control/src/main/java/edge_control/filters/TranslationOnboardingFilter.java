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
 * APISIX plugin filter that handles device translation config management.
 * Handles POST (create/update), DELETE, and GET on the /onboarding/translation route.
 */
@Component
public class TranslationOnboardingFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(TranslationOnboardingFilter.class);

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static final DeviceTranslationManager deviceTranslationManager =
            DeviceTranslationManager.getInstance();

    private static final RequestHandler requestHandler = RequestHandler.getInstance();

    TranslationOnboardingFilter() {
        logger.info("TranslationOnboarding Filter initialized");
        API_LOGGER.warn("TranslationOnboarding Filter is running");
    }

    @Override
    public String name() {
        return "TranslationOnboarding";
    }

    /**
     * Routes requests to the device management handler.
     * Only /onboarding/translation is accepted — all other paths throw IllegalOperation.
     *
     * @param request  Inbound HTTP request
     * @param response HTTP response to populate
     * @param chain    APISIX filter chain
     */
    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {

        logger.debug("Incoming request in " + name() + ", index: " + chain.getIndex());

        requestHandler.register(request);

        // A previous filter may have marked this request to be skipped
        if (requestHandler.shouldSkipRequest(request, chain)) {
            chain.filter(request, response);
            return;
        }

        try {
            if (request.getPath().startsWith("/onboarding/translation")) {
                handleDeviceManagementRequest(request, response, chain);
            } else {
                throw new IllegalOperation(
                        "TranslationOnboarding filter is available for /onboarding/translation route.");
            }
        } catch (Exception e) {
            // Synchronous error — handle inline and continue chain
            ExceptionHandler.handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
        }
    }

    /**
     * Handles CRUD operations on device translation configs.
     * POST creates or updates, DELETE removes, GET retrieves the current config.
     *
     * @throws OperationNotSupported  If the HTTP method is not POST, DELETE, or GET
     * @throws CorruptedConfiguration If the request body is malformed
     * @throws EdgeControlException   If the underlying registry operation fails
     */
    private void handleDeviceManagementRequest(HttpRequest request,
                                               HttpResponse response,
                                               PluginFilterChain chain)
            throws OperationNotSupported, CorruptedConfiguration, EdgeControlException {

        switch (request.getMethod()) {

            case POST -> {
                // Create or update the device translation config and initialise its adapter
                deviceTranslationManager.createAdapter(request.getBody());
                response.setStatusCode(200);
                response.setBody("Device Translation Created");
                chain.filter(request, response);
            }

            case DELETE -> {
                // Delete returns false if the device was not found in the DB
                if (deviceTranslationManager.deleteDeviceConfig(request.getBody())) {
                    response.setStatusCode(200);
                    response.setBody("Device Translation Config Deleted - success");
                } else {
                    response.setStatusCode(404);
                    response.setHeader("X-Error", "Unknown device - no config to delete");
                    response.setBody("X-Error: Unknown device - no config to delete");
                }
                chain.filter(request, response);
            }

            case GET -> {
                DeviceConfig deviceConfig = deviceTranslationManager.getConfig(request.getBody());
                if (deviceConfig == null) {
                    response.setStatusCode(404);
                    response.setHeader("X-Error", "Unknown device - no config to retrieve");
                    response.setBody("X-Error: Unknown device - no config to retrieve");
                } else {
                    // toString() strips the internal _id field before returning
                    response.setStatusCode(200);
                    response.setBody(deviceConfig.toString());
                }
                chain.filter(request, response);
            }

            default -> throw new OperationNotSupported(
                    "Method: " + request.getMethod() + " not supported on route /onboarding/translation");
        }
    }

    @Override
    public Boolean requiredBody() {
        return true;
    }
}