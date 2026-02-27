package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.backend.BackendManager;
import edge_control.device.DeviceManager;
import edge_control.exceptions.CorruptedConfiguration;
import edge_control.exceptions.IllegalOperation;
import edge_control.exceptions.OperationNotSupported;
import edge_control.logger.EdgeControlLogger;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.apache.apisix.plugin.runner.filter.PluginFilter;
import org.apache.apisix.plugin.runner.filter.PluginFilterChain;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
public class OnboardingFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(OnboardingFilter.class);

    private final EdgeControlLogger logger =
            EdgeControlLogger.getInstance();

    private final BackendManager backendManager =
            BackendManager.getInstance();

    private final DeviceManager deviceManager =
            DeviceManager.getInstance();

    private static final RequestHandler requestHandler =
            RequestHandler.getInstance();

    /**
     * Initializes the plugin and logs startup messages.
     */
    OnboardingFilter() {
        logger.info("Onboarding Filter initialized");
        API_LOGGER.warn("Onboarding Filter is running");
    }

    /**
     * Returns the name of this plugin filter.
     *
     * @return plugin name
     */
    @Override
    public String name() {
        return "Onboarding";
    }

    /**
     * Main filter method invoked by APISIX.
     * Routes requests to onboarding for adding a backend or a device, but also for configuring the authorizations.
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

        switch (request.getMethod()){
            case POST -> {
                try {
                    if (request.getPath().endsWith("/backend")) {
                        logger.debug("addBackend reached");
                        this.handleBackendCommunicationConfig(request,response, "POST");

                    } else if (request.getPath().endsWith("/backendAuthZ")) {
                        logger.debug("authorization backend reached");
                        this.handleBackendAuthorizationConfig(request,response, "POST");

                    } else if (request.getPath().endsWith("/device")) {
                        logger.debug("addDevice reached");
                        this.handleDeviceCommunicationConfig(request,response, "POST");

                    } else if (request.getPath().endsWith("/deviceAuthZ")) {
                        logger.debug("authorization device reached");
                        this.handleDeviceAuthorizationConfig(request,response, "POST");

                    }
                } catch (Exception e) {
                    this.handleException(response, e);
                }
            } case PATCH -> {
                try {
                    if (request.getPath().endsWith("/backendAuthZ")) {
                        logger.debug("patch authorization backend reached");
                        this.handleBackendAuthorizationConfig(request,response, "PATCH");

                    }
                } catch (Exception e) {
                    this.handleException(response, e);
                }
            } case DELETE -> {
                try {
                    if (request.getPath().endsWith("/backend")) {
                        logger.debug("deleteBackend reached");
                        this.handleBackendCommunicationConfig(request,response, "DELETE");
                    } else if (request.getPath().endsWith("/backendAuthZ")) {
                        logger.debug("delete authorization backend reached");
                        this.handleBackendAuthorizationConfig(request,response, "DELETE");
                    } else if (request.getPath().endsWith("/deviceAuthZ")) {
                        logger.debug("delete authorization device reached");
                        this.handleDeviceAuthorizationConfig(request,response, "DELETE");
                    } else if (request.getPath().endsWith("/device")) {
                        logger.debug("deleteDevice reached");
                        this.handleDeviceCommunicationConfig(request,response, "DELETE");

                    }
                } catch (Exception e) {
                    this.handleException(response, e);
                }

            } case GET -> {

            }
            default -> {
                logger.debug("Received unsupported HTTP method: " + request.getMethod());
            }
        }


        // Continue APISIX chain
        chain.filter(request, response);
    }

    /**
     * Handles the creation of a new backend based on the request body.
     *
     * @param request the incoming HTTP request containing the backend configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleBackendCommunicationConfig(HttpRequest request, HttpResponse response, String method) throws Exception {
        Document gatewayBackendInfo = new Document();
        Document gatewayDeviceAuthzInfo = new Document();

        if (method.equals("POST")) {
            gatewayBackendInfo = backendManager.createBackend(request.getBody());
        } else if (method.equals("DELETE")) {
            gatewayBackendInfo = backendManager.deleteBackend(request.getBody());
            gatewayDeviceAuthzInfo = deviceManager.removeAllBackendsFromAuthorization(request.getBody());
        }

        Document resp = new Document();
        if (gatewayDeviceAuthzInfo.isEmpty() && !gatewayBackendInfo.isEmpty()){
            response.setBody(gatewayBackendInfo.toJson());
            response.setStatusCode(200);
        } else if (!gatewayBackendInfo.isEmpty() && (gatewayBackendInfo.getString("status").equals("failure") || gatewayBackendInfo.getString("status").equals("partial_failure")  || gatewayDeviceAuthzInfo.getString("status").equals("failure"))) {
            resp.put("status", "failure");
            resp.put("message", gatewayBackendInfo.getString("message"));
            response.setStatusCode(400);
            response.setBody(resp.toJson());
        } else {
            resp.put("status", "success");
            resp.put("gatewayBackendInfo", gatewayBackendInfo);
            resp.put("gatewayDeviceAuthzInfo", gatewayDeviceAuthzInfo);
            response.setStatusCode(200);
            response.setBody(resp.toJson());
        }

        requestHandler.skipChain(request);
    }

    /**
     * Handles the configuration of backend authorization based on the request body.
     *
     * @param request the incoming HTTP request containing the authorization configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleBackendAuthorizationConfig(HttpRequest request, HttpResponse response, String method) throws Exception {
        Document resp = new Document();
        if (method.equals("POST")){
            resp = backendManager.addBackendAuthorizationConfig(request.getBody());
        } else if (method.equals("PATCH")){
            resp = backendManager.updateBackendAuthorizationConfig(request.getBody());
        } else if (method.equals("DELETE")){
            resp = backendManager.deleteBackendAuthorizationConfig(request.getBody());
        }

        if (resp.get("status").equals("success")){
            response.setStatusCode(200);
        } else {
            response.setStatusCode(400);
        }
        response.setBody(resp.toJson());
        requestHandler.skipChain(request);
    }

    /**
     * Handles the creation of a new device based on the request body.
     *
     * @param request the incoming HTTP request containing the device configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleDeviceCommunicationConfig(HttpRequest request, HttpResponse response, String method) throws Exception {
        Document gatewayDeviceInfo = new Document();
        Document gatewayBackendAuthzInfo = new Document();

        if (method.equals("POST")){
            gatewayDeviceInfo = deviceManager.createDevice(request.getBody());
        } else if (method.equals("DELETE")){
            gatewayDeviceInfo = deviceManager.deleteDevice(request.getBody());
            gatewayBackendAuthzInfo = backendManager.removeAllDevicesFromAuthorization(request.getBody());
        }

        Document resp = new Document();
        if (gatewayBackendAuthzInfo.isEmpty() && !gatewayDeviceInfo.isEmpty()){
            response.setBody(gatewayDeviceInfo.toJson());
            response.setStatusCode(200);
        } else if (!gatewayDeviceInfo.isEmpty() && (gatewayDeviceInfo.getString("status").equals("failure") || gatewayDeviceInfo.getString("status").equals("partial_failure")  || gatewayBackendAuthzInfo.getString("status").equals("failure"))) {
            resp.put("status", "failure");
            resp.put("message", gatewayDeviceInfo.getString("message"));
            response.setStatusCode(400);
            response.setBody(resp.toJson());
        } else {
            resp.put("status", "success");
            resp.put("gatewayBackendInfo", gatewayDeviceInfo);
            resp.put("gatewayDeviceAuthzInfo", gatewayBackendAuthzInfo);
            response.setStatusCode(200);
            response.setBody(resp.toJson());
        }

        requestHandler.skipChain(request);
    }

    /**
     * Handles the configuration of device authorization based on the request body.
     *
     * @param request the incoming HTTP request containing the authorization configuration
     * @param response the HTTP response to populate
     * @throws CorruptedConfiguration if the configuration is invalid
     */
    private void handleDeviceAuthorizationConfig(HttpRequest request, HttpResponse response, String method) throws Exception {
        Document resp = new Document();
        if (method.equals("POST")){
            resp = deviceManager.addDeviceAuthorizationConfig(request.getBody());
        } else if (method.equals("DELETE")){
            resp = deviceManager.deleteDeviceAuthorizationConfig(request.getBody());
        }

        if (resp.get("status").equals("success")){
            response.setStatusCode(200);
        } else {
            response.setStatusCode(400);
        }
        response.setBody(resp.toJson());
        requestHandler.skipChain(request);
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
