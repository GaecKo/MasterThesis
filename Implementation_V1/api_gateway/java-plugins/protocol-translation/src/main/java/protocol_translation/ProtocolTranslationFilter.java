package protocol_translation;

import java.util.ArrayList;
import java.util.List;

import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import protocol_translation.device_registry.DeviceRegistry;
import protocol_translation.device_adapter.DeviceAdapter;
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

    private final DeviceRegistry deviceRegistry =
            DeviceRegistry.getInstance(); // or inject if Spring-managed

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

        response.setStatusCode(400);
        response.setHeader("X-Error", "Missing X-Device-Id header");
        response.setBody("");

        chain.filter(request, response);
        return;

        // Mark request as processed
        // request.setHeader("X-Processed-By", "Java-plugins:ProtocolTranslation");
        /*
        try {
            // Management endpoints
            if (request.getPath().startsWith("/devices")) {
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

         */
    }

    private void handleDeviceRequest(HttpRequest request,
                                     HttpResponse response) throws Exception {

        String deviceId = request.getHeader("X-Device-Id");

        logger.debug("Handling device !!!!!!");

        if (deviceId == null || deviceId.isBlank()) {
            logger.debug("No device ID...");
            response.setStatusCode(400);
            response.setHeader("X-Error", "Missing X-Device-Id header");
            response.setBody("");
            return;
        }

        DeviceAdapter adapter = deviceRegistry.get(deviceId);

        if (adapter == null) {
            response.setStatusCode(404);
            response.setHeader("X-Error", "Unknown device: " + deviceId);
            response.setBody("");
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

        response.setStatusCode(501);
        response.setHeader("X-Error", "Device management not implemented yet");
    }

    private void handleException(HttpResponse response, Exception e) {
        logger.error("Request failed: " + e);

        if (e instanceof CorruptedConfiguration) {
            response.setStatusCode(400);
            response.setHeader("X-Error", "CorruptedConfiguration: " + e.getMessage());
            response.setBody("");

        } else if (e instanceof IllegalOperation) {
            response.setStatusCode(403);
            response.setHeader("X-Error", "IllegalOperation: " + e.getMessage());
            response.setBody("");

        } else if (e instanceof OperationNotSupported) {
            response.setStatusCode(501);
            response.setHeader("X-Error", "OperationNotSupported" + e.getMessage());
            response.setBody("");

        } else {
            response.setStatusCode(500);
            response.setHeader("X-Error", "InternalError" + e.getMessage());
            response.setBody("");
        }
    }


    @Override
    public List<String> requiredVars() {
        List<String> vars = new ArrayList<>();
        vars.add("remote_addr");
        vars.add("server_port");
        return vars;
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
