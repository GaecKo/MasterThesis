package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.device.DeviceManager;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.EdgeControlException;
import edge_control.exceptions.IllegalOperation;
import edge_control.exceptions.OperationNotSupported;
import edge_control.logger.EdgeControlLogger;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeviceConfigFilter implements PluginFilter {
    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(DeviceConfigFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private final DeviceManager deviceManager =
            DeviceManager.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();

    /**
     * Initializes the plugin and logs startup messages.
     */
    DeviceConfigFilter() {
        logger.info("DeviceConfig Filter initialized");
        API_LOGGER.warn("DeviceConfig Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "DeviceConfig";
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

        try {
            if (request.getPath().startsWith("/devices")) {
                // Management endpoints
                handleManagementRequest(request, response);

            } else {
                // stop chain
                requestHandler.skipChain(request);
                throw new EdgeControlException("Cannot use another route than /devices with the DeviceConfig filter. " +
                        "Used route: " + request.getPath());

            }
        } catch (Exception e) {
            handleException(response, e);
        }

        // Continue APISIX chain
        chain.filter(request, response);
    }

    /**
     * Handles device management requests (e.g., create/update/remove adapters).
     * Currently supports only POST for creating adapters; other methods return 501.
     *
     * @param request the incoming HTTP request
     * @param response the HTTP response to populate
     */
    private void handleManagementRequest(HttpRequest request,
                                         HttpResponse response) throws CorruptedConfiguration, EdgeControlException {

        // Placeholder for:
        // POST /devices → add/update device
        // DELETE /devices/{id} → remove device
        // GET /devices → list devices

        switch (request.getMethod()) {
            // create new device
            case POST: {

                deviceManager.createAdapter(request.getBody());
                response.setStatusCode(200);
                response.setBody("Device Adapter created!");
                return;
            }
        }

        response.setStatusCode(501);
        response.setHeader("X-Error", "Device management not implemented yet");
        response.setBody("Device management not implemented yet");
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
