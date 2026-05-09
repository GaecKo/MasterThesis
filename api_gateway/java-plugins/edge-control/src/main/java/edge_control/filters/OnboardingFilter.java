package edge_control.filters;

import edge_control.RequestHandler;
import edge_control.auth.AuthenticationManager;
import edge_control.auth.backend.BackendManager;
import edge_control.auth.device.DeviceManager;
import edge_control.exceptions.EdgeControlException;
import edge_control.exceptions.ExceptionHandler;
import edge_control.exceptions.IllegalOperation;
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
 * APISIX plugin filter for onboarding backends, devices, and authorization configs.
 * Routes POST/PATCH/DELETE to the appropriate manager based on the request path suffix.
 */
@Component
public class OnboardingFilter implements PluginFilter {

    private static final Logger API_LOGGER =
            LoggerFactory.getLogger(OnboardingFilter.class);

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static final BackendManager backendManager;

    static {
        try {
            backendManager = BackendManager.getInstance();
        } catch (EdgeControlException e) {
            throw new RuntimeException(e);
        }
    }

    private static final DeviceManager deviceManager = DeviceManager.getInstance();

    private static final AuthenticationManager authenticationManager =
            AuthenticationManager.getInstance();

    private static final RequestHandler requestHandler = RequestHandler.getInstance();

    OnboardingFilter() {
        logger.info("Onboarding Filter initialized");
        API_LOGGER.warn("Onboarding Filter is running");
    }

    @Override
    public String name() {
        return "Onboarding";
    }

    /**
     * Routes inbound requests to the appropriate handler based on HTTP method and path suffix.
     * All exceptions from handlers are caught centrally and mapped to HTTP error responses.
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
            // Route based on method first, then path suffix
            switch (request.getMethod()) {
                case POST -> {
                    if (request.getPath().endsWith("/backend")) {
                        handleBackendCommunicationConfig(request, response, "POST");
                    } else if (request.getPath().endsWith("/backendAuthZ")) {
                        handleBackendAuthorizationConfig(request, response, "POST");
                    } else if (request.getPath().endsWith("/device")) {
                        handleDeviceCommunicationConfig(request, response, "POST");
                    } else if (request.getPath().endsWith("/deviceAuthZ")) {
                        handleDeviceAuthorizationConfig(request, response, "POST");
                    }
                }
                case PATCH -> {
                    if (request.getPath().endsWith("/backend")) {
                        handleBackendCommunicationConfig(request, response, "PATCH");
                    } else if (request.getPath().endsWith("/backendAuthZ")) {
                        handleBackendAuthorizationConfig(request, response, "PATCH");
                    } else if (request.getPath().endsWith("/device")) {
                        handleDeviceCommunicationConfig(request, response, "PATCH");
                    } else if (request.getPath().endsWith("/deviceAuthZ")) {
                        handleDeviceAuthorizationConfig(request, response, "PATCH");
                    }
                }
                case DELETE -> {
                    if (request.getPath().endsWith("/backend")) {
                        handleBackendCommunicationConfig(request, response, "DELETE");
                    } else if (request.getPath().endsWith("/backendAuthZ")) {
                        handleBackendAuthorizationConfig(request, response, "DELETE");
                    } else if (request.getPath().endsWith("/device")) {
                        handleDeviceCommunicationConfig(request, response, "DELETE");
                    } else if (request.getPath().endsWith("/deviceAuthZ")) {
                        handleDeviceAuthorizationConfig(request, response, "DELETE");
                    }
                }
                case GET -> {
                    if (request.getPath().endsWith("/commands")) {
                        handleBackendAuthorizedCommands(request, response);
                    }
                }
                default -> logger.debug("Received unsupported HTTP method: " + request.getMethod());
            }
        } catch (Exception e) {
            // Central catch — all handler exceptions are mapped to HTTP error responses here
            ExceptionHandler.handleException(response, e);
        }

        chain.filter(request, response);
    }

    // | ================= Communication config handlers ================= |

    /**
     * Handles POST/PATCH/DELETE for backend communication config.
     * On DELETE, also removes the backend from all device authorization configs.
     *
     * @param method "POST", "PATCH", or "DELETE"
     */
    private void handleBackendCommunicationConfig(HttpRequest request,
                                                  HttpResponse response,
                                                  String method) throws Exception {
        Document gatewayBackendInfo  = new Document();
        Document gatewayDeviceAuthzInfo = new Document();

        if (method.equals("POST")) {
            gatewayBackendInfo = backendManager.createBackend(request.getBody());
        } else if (method.equals("PATCH")) {
            gatewayBackendInfo = backendManager.updateBackend(request.getBody());
        } else if (method.equals("DELETE")) {
            gatewayBackendInfo = backendManager.deleteBackend(request.getBody());
            // Also clean up any device authorization entries referencing this backend
            gatewayDeviceAuthzInfo = deviceManager.removeAllBackendsFromAuthorization(request.getBody());
        }

        Document resp = new Document();
        if (gatewayDeviceAuthzInfo.isEmpty() && !gatewayBackendInfo.isEmpty()) {
            // Simple operation (no cascading authz cleanup) — return backend result directly
            response.setBody(gatewayBackendInfo.toJson());
            response.setStatusCode(200);
        } else if (!gatewayBackendInfo.isEmpty()
                && (gatewayBackendInfo.getString("status").equals("failure")
                || gatewayBackendInfo.getString("status").equals("partial_failure")
                || gatewayDeviceAuthzInfo.getString("status").equals("failure"))) {
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
     * Handles POST/PATCH/DELETE for backend authorization config.
     *
     * @param method "POST", "PATCH", or "DELETE"
     */
    private void handleBackendAuthorizationConfig(HttpRequest request,
                                                  HttpResponse response,
                                                  String method) throws Exception {
        Document resp = new Document();
        if (method.equals("POST")) {
            resp = backendManager.addBackendAuthorizationConfig(request.getBody());
        } else if (method.equals("PATCH")) {
            resp = backendManager.updateBackendAuthorizationConfig(request.getBody());
        } else if (method.equals("DELETE")) {
            resp = backendManager.deleteBackendAuthorizationConfig(request.getBody());
        }

        response.setStatusCode(resp.get("status").equals("success") ? 200 : 400);
        response.setBody(resp.toJson());
        requestHandler.skipChain(request);
    }

    /**
     * Handles POST/PATCH/DELETE for device communication config.
     * On DELETE, also removes the device from all backend authorization configs.
     *
     * @param method "POST", "PATCH", or "DELETE"
     */
    private void handleDeviceCommunicationConfig(HttpRequest request,
                                                 HttpResponse response,
                                                 String method) throws Exception {
        Document gatewayDeviceInfo      = new Document();
        Document gatewayBackendAuthzInfo = new Document();

        if (method.equals("POST")) {
            gatewayDeviceInfo = deviceManager.createDevice(request.getBody());
        } else if (method.equals("PATCH")) {
            gatewayDeviceInfo = deviceManager.updateDevice(request.getBody());
        } else if (method.equals("DELETE")) {
            gatewayDeviceInfo = deviceManager.deleteDevice(request.getBody());
            // Also clean up any backend authorization entries referencing this device
            gatewayBackendAuthzInfo = backendManager.removeAllDevicesFromAuthorization(request.getBody());
        }

        Document resp = new Document();
        if (gatewayBackendAuthzInfo.isEmpty() && !gatewayDeviceInfo.isEmpty()) {
            // Simple operation (no cascading authz cleanup) — return device result directly
            response.setBody(gatewayDeviceInfo.toJson());
            response.setStatusCode(200);
        } else if (!gatewayDeviceInfo.isEmpty()
                && (gatewayDeviceInfo.getString("status").equals("failure")
                || gatewayDeviceInfo.getString("status").equals("partial_failure")
                || gatewayBackendAuthzInfo.getString("status").equals("failure"))) {
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
     * Handles POST/PATCH/DELETE for device authorization config.
     *
     * @param method "POST", "PATCH", or "DELETE"
     */
    private void handleDeviceAuthorizationConfig(HttpRequest request,
                                                 HttpResponse response,
                                                 String method) throws Exception {
        Document resp = new Document();
        if (method.equals("POST")) {
            resp = deviceManager.addDeviceAuthorizationConfig(request.getBody());
        } else if (method.equals("PATCH")) {
            resp = deviceManager.updateDeviceAuthorizationConfig(request.getBody());
        } else if (method.equals("DELETE")) {
            resp = deviceManager.deleteDeviceAuthorizationConfig(request.getBody());
        }

        response.setStatusCode(resp.get("status").equals("success") ? 200 : 400);
        response.setBody(resp.toJson());
        requestHandler.skipChain(request);
    }

    // | ================= Query handlers ================= |

    /**
     * Returns all authorized commands and params for all devices linked to the requesting backend.
     * The backend is identified via its API key in the request header.
     */
    private void handleBackendAuthorizedCommands(HttpRequest request,
                                                 HttpResponse response) throws Exception {
        String authResult = authenticationManager.checkAuthentication(request.getHeader("apikey"));

        // Only backends may query authorized commands — reject devices and invalid keys
        if (authResult.startsWith("device_")) {
            ExceptionHandler.handleException(response,
                    new IllegalOperation("Unauthorized access: API key belongs to a device"));
            return;
        } else if (authResult.startsWith("Invalid API key")) {
            ExceptionHandler.handleException(response, new IllegalOperation(authResult));
            return;
        }

        Document resp = new Document();
        if (authResult.startsWith("backend_")) {
            resp = backendManager.getBackendAuthorizedCommands(authResult);
        }

        logger.info(resp.toJson());

        response.setStatusCode(resp.get("status").equals("success") ? 200 : 400);
        response.setBody(resp.toJson());
        requestHandler.skipChain(request);
    }

    @Override
    public Boolean requiredBody() {
        return true;
    }
}