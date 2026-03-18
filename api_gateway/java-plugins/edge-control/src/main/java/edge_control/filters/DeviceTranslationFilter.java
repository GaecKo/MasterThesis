package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.translation.queuing.QueueRegistry;
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
import edge_control.translation.adapter.AdapterCallback;
import edge_control.translation.adapter.DeviceAdapter;
import edge_control.exceptions.*;

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

    private static final QueueRegistry queueRegistry = QueueRegistry.getInstance();

    DeviceTranslationFilter() {
        logger.info("DeviceTranslation Filter initialized");
        API_LOGGER.warn("DeviceTranslation Filter is running");
    }

    @Override
    public String name() {
        return "DeviceTranslation";
    }

    @Override
    public void filter(HttpRequest request,
                       HttpResponse response,
                       PluginFilterChain chain) {

        logger.debug("Incoming request in " + name() + ", index: " + chain.getIndex());

        requestHandler.register(request);

        if (requestHandler.shouldSkipRequest(request, chain)) {
            chain.filter(request, response);
            return;
        }

        try {
            if (request.getPath().startsWith("/command")
                    && request.getMethod() == HttpRequest.Method.POST) {
                handleDeviceRequestAsync(request, response, chain);
            } else {
                throw new IllegalOperation(
                        "DeviceTranslation filter is available for POST on /command route.");
            }
        } catch (Exception e) {
            ExceptionHandler.handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
        }
    }

    private void handleDeviceRequestAsync(HttpRequest request,
                                          HttpResponse response,
                                          PluginFilterChain chain) {
        try {
            JSONObject config = new JSONObject(request.getBody());

            if (!config.has("gatewayDeviceId")) {
                response.setStatusCode(400);
                response.setHeader("X-Error", "Missing gatewayDeviceId in request body");
                response.setBody("X-Error: Missing gatewayDeviceId in body");
                chain.filter(request, response);
                return;
            }

            String gatewayDeviceId = config.getString("gatewayDeviceId");
            DeviceAdapter adapter = deviceTranslationManager.get(gatewayDeviceId);

            if (adapter == null) {
                response.setStatusCode(404);
                response.setHeader("X-Error", "Unknown device - no adapter linked: " + gatewayDeviceId);
                response.setBody("X-Error: Unknown device - no adapter linked: " + gatewayDeviceId);
                requestHandler.skipChain(request);
                chain.filter(request, response);
                return;
            }

            adapter.handleRequest(request, response, new AdapterCallback() {
                @Override
                public void onSuccess() {
                    try {
                        chain.filter(request, response);
                    } catch (Exception e) {
                        logger.error("Error in chain.filter callback: " + e.getMessage());
                    }
                }

                @Override
                public void onDeviceUnreachable(String reason) {
                    try {
                        if (queueRegistry.hasQueuing(gatewayDeviceId)) {
                            queueRegistry.enqueue(gatewayDeviceId,
                                    request.getBody(), request.getHeaders());
                            response.setStatusCode(202);
                            response.setBody("{\"status\":\"queued\","
                                    + "\"message\":\"Device unreachable — request queued for retry\","
                                    + "\"deviceId\":\"" + gatewayDeviceId + "\"}");
                            response.setHeader("MODIFIED-BY", "EdgeControl/Queuing");
                            logger.info("Request queued for device " + gatewayDeviceId
                                    + ": " + reason);
                        } else {
                            response.setStatusCode(502);
                            response.setBody("{\"error\":\"Device unreachable: " + reason + "\"}");
                            logger.info("Device unreachable, no queuing configured: "
                                    + gatewayDeviceId);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to enqueue request for device "
                                + gatewayDeviceId + ": " + e.getMessage());
                        response.setStatusCode(502);
                        response.setBody("{\"error\":\"Device unreachable and queuing failed: "
                                + reason + "\"}");
                    } finally {
                        chain.filter(request, response);
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Error in handleDeviceRequestAsync: " + e.getMessage());
            ExceptionHandler.handleException(response, e);
            chain.filter(request, response);
        }
    }

    @Override
    public Boolean requiredBody() { return true; }

    @Override
    public Boolean requiredRespBody() { return true; }
}