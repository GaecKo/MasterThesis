package protocol_translation;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import protocol_translation.device.DeviceManager;
import protocol_translation.device.adapter.DeviceAdapter;
import protocol_translation.exceptions.CorruptedConfiguration;
import protocol_translation.exceptions.IllegalOperation;
import protocol_translation.exceptions.OperationNotSupported;
import protocol_translation.logger.ProtocolTranslationLogger;

@Component
public class ProtocolTranslationFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(ProtocolTranslationFilter.class);

    private final ProtocolTranslationLogger logger =
            ProtocolTranslationLogger.getInstance();

    private final DeviceManager deviceManager =
            DeviceManager.getInstance();

    ProtocolTranslationFilter() {
        logger.info("Protocol Translation initialized");
        API_LOGGER.warn("ProtocolTranslation plugin is running");
    }

    @Override
    public String name() {
        return "ProtocolTranslation";
    }

    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {

        logger.debug("Incoming request");
        logger.debug("Path: " + request.getPath());
        logger.debug("Method: " + request.getMethod());
        logger.debug("Source IP: " + request.getSourceIP());

        // Mark request as processed
        request.setHeader("X-Processed-By", "Java-plugins:ProtocolTranslation");

        try {

            if (request.getPath().startsWith("/health")) {
                logger.debug("Health endpoint reached");
                response.setStatusCode(200);
                response.setBody("Health Check reacted!\n");

            } else if (request.getPath().startsWith("/devices")) {
                // Management endpoints
                handleManagementRequest(request, response);

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

        DeviceAdapter adapter = deviceManager.get(deviceId);

        if (adapter == null) {
            response.setStatusCode(404);
            response.setHeader("X-Error", "Unknown device: " + deviceId);
            response.setBody("X-Error: Unknown device: ");
            return;
        }

        // for later: (smth similar)
        // DeviceRequest devReq = DeviceRequest.fromHttp(request);
        // DeviceResponse devRes = adapter.handle(devReq);
        // devRes.applyTo(response);

        logger.debug("Request routed to device" + deviceId);
    }

    private void handleManagementRequest(HttpRequest request,
                                         HttpResponse response) {

        // Placeholder for:
        // POST /devices → add/update device
        // DELETE /devices/{id} → remove device
        // GET /devices → list devices

        switch (request.getMethod()) {
            // create new device
            case POST: {
                try {
                    deviceManager.createAdapter(request.getBody());
                    response.setStatusCode(200);
                    response.setBody("Adapter for device created!\n");
                } catch (Exception e) {
                    response.setStatusCode(500);
                    response.setHeader("X-Error", "Server Error, adapter creation failed: " + e.getMessage());
                    response.setBody("Server Error, adapter creation failed: " + e.getMessage() + "\n");
                }


                return;
            }
        }

        response.setStatusCode(501);
        response.setHeader("X-Error", "Device management not implemented yet");
        response.setBody("Device management not implemented yet");
    }

    private void handleException(HttpResponse response, Exception e) {
        logger.error("Request failed: " + e);

        switch (e) {
            case CorruptedConfiguration corruptedConfiguration -> {
                response.setStatusCode(400);
                response.setHeader("X-Error", "CorruptedConfiguration: " + e.getMessage());
                response.setBody("CorruptedConfiguration: " + e.getMessage());
            }
            case IllegalOperation illegalOperation -> {
                response.setStatusCode(403);
                response.setHeader("X-Error", "IllegalOperation: " + e.getMessage());
                response.setBody("IllegalOperation: " + e.getMessage());
            }
            case OperationNotSupported operationNotSupported -> {
                response.setStatusCode(501);
                response.setHeader("X-Error", "OperationNotSupported" + e.getMessage());
                response.setBody("OperationNotSupported" + e.getMessage());
            }
            default -> {
                response.setStatusCode(500);
                response.setHeader("X-Error", "InternalError" + e.getMessage());
                response.setBody("InternalError" + e.getMessage());
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
