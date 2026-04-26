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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * APISIX plugin filter that routes POST /command requests to the appropriate device adapter.
 *
 * Translates the backend command into a device-specific protocol, handles the async response,
 * and enqueues the request for retry if the device is unreachable and queuing is configured.
 */
@Component
public class DeviceTranslationFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(DeviceTranslationFilter.class);

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static final DeviceTranslationManager deviceTranslationManager =
            DeviceTranslationManager.getInstance();

    private static final RequestHandler requestHandler = RequestHandler.getInstance();

    private static final QueueRegistry queueRegistry = QueueRegistry.getInstance();

    private static final DateTimeFormatter TIMING_FORMATTER =
            DateTimeFormatter.ofPattern("hh:mm:ss.SSSS").withZone(ZoneId.systemDefault());

    DeviceTranslationFilter() {
        logger.info("DeviceTranslation Filter initialized");
        API_LOGGER.warn("DeviceTranslation Filter is running");
    }

    @Override
    public String name() {
        return "DeviceTranslation";
    }

    /**
     * Entry point for the filter. Only processes POST /command requests.
     * Must never block the Netty event loop thread.
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

        // A previous filter may have marked this request to be skipped (e.g. auth failure)
        if (requestHandler.shouldSkipRequest(request, chain)) {
            logger.debug("Skipping request in " + name() + ", index: " + chain.getIndex());
            chain.filter(request, response);
            return;
        }

        int reqHash = request.hashCode();
        Instant start = Instant.now();
        logger.time("Trans Filter: request arrived, wasn't skipped (" + reqHash + ")");

        try {
            if (request.getPath().startsWith("/command")
                    && request.getMethod() == HttpRequest.Method.POST) {
                // Slow path — async, returns immediately and continues chain via callback
                handleDeviceRequestAsync(request, response, chain, start);
            } else {
                throw new IllegalOperation(
                        "DeviceTranslation filter is available for POST on /command route.");
            }
        } catch (Exception e) {
            // Synchronous error before async work starts — handle inline and continue chain
            ExceptionHandler.handleException(response, e);
            requestHandler.skipChain(request);
            chain.filter(request, response);
        }
    }

    /**
     * Resolves the adapter for the target device and delegates the request to it asynchronously.
     * Handles queueing via AdapterCallback if the device is unreachable.
     *
     * @param request  Inbound request; body must contain 'gatewayDeviceId'
     * @param response HTTP response to populate
     * @param chain    APISIX filter chain
     * @param start    Timestamp of filter entry, used for timing logs
     */
    private void handleDeviceRequestAsync(HttpRequest request,
                                          HttpResponse response,
                                          PluginFilterChain chain,
                                          Instant start) {
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

            // callbackEndpoint is consumed here by the queuing layer — strip it before
            // forwarding so the device does not receive an unexpected field
            String callbackEndpoint = null;
            if (config.has("callbackEndpoint")) {
                callbackEndpoint = config.getString("callbackEndpoint");
                config.remove("callbackEndpoint");
            }

            // Write the cleaned body back so the adapter sees it without internal fields
            request.setBody(config.toString());

            DeviceAdapter adapter = deviceTranslationManager.get(gatewayDeviceId);
            if (adapter == null) {
                // No adapter means the device was never onboarded or was deleted
                response.setStatusCode(404);
                response.setHeader("X-Error", "Unknown device - no adapter linked: " + gatewayDeviceId);
                response.setBody("X-Error: Unknown device - no adapter linked: " + gatewayDeviceId);
                requestHandler.skipChain(request);
                chain.filter(request, response);
                return;
            }

            // Must be final to be captured by the anonymous AdapterCallback class below
            final String finalCallbackEndpoint = callbackEndpoint;

            adapter.handleRequest(request, response, new AdapterCallback() {
                @Override
                public void onSuccess() {
                    try {
                        Instant end = Instant.now();
                        logger.time("Trans Filter: request processed - time took:"
                                + (end.toEpochMilli() - start.toEpochMilli()) + "ms (" + request.hashCode() + ")");
                        // Response already set by the adapter — continue the chain
                        chain.filter(request, response);
                    } catch (Exception e) {
                        logger.error("Error in chain.filter callback: " + e.getMessage());
                    }
                }

                @Override
                public void onDeviceUnreachable(String reason) {
                    try {
                        if (queueRegistry.hasQueuing(gatewayDeviceId)) {
                            // Persist the request for retry and return 202 to the caller
                            String queuedRequestId = queueRegistry.enqueue(
                                    gatewayDeviceId, finalCallbackEndpoint,
                                    request.getBody(), request.getHeaders());
                            response.setStatusCode(202);
                            response.setBody("{\"status\":\"queued\","
                                    + "\"message\":\"Device unreachable - request queued for retry\","
                                    + "\"deviceId\":\"" + gatewayDeviceId + "\","
                                    + "\"queuedRequestId\":\"" + queuedRequestId + "\"}");
                            response.setHeader("MODIFIED-BY", "EdgeControl/Queuing");
                            logger.info("Request queued for device " + gatewayDeviceId + ": " + reason);
                        } else {
                            // Device is down and no retry mechanism is configured — fail immediately
                            response.setStatusCode(502);
                            response.setBody("{\"error\":\"Device unreachable: " + reason
                                    + ", device has no queuing mechanism configured\"}");
                            logger.warn("Device unreachable, no queuing configured: " + gatewayDeviceId);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to enqueue request for device "
                                + gatewayDeviceId + ": " + e.getMessage());
                        response.setStatusCode(502);
                        response.setBody("{\"error\":\"Device unreachable and queuing failed: " + reason + "\"}");
                    } finally {
                        // Always continue the chain, even on queuing failure
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
}