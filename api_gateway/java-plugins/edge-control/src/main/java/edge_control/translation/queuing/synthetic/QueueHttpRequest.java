package edge_control.translation.queuing.synthetic;

import io.github.api7.A6.HTTPReqCall.Req;
import org.apache.apisix.plugin.runner.HttpRequest;

import java.util.Map;

/**
 * Synthetic HttpRequest used during queue retries.
 * Wraps a stored request body and headers so the adapter can be called
 * without a real APISIX HttpRequest object.
 */
public class QueueHttpRequest extends HttpRequest {

    private final String              body;
    private final Map<String, String> headers;

    /**
     * @param body    Original request body stored when the request was enqueued
     * @param headers Original request headers stored when the request was enqueued
     */
    public QueueHttpRequest(String body, Map<String, String> headers) {
        super(new Req());
        this.body    = body;
        this.headers = headers;
    }

    @Override public String              getBody()    { return body; }
    @Override public Map<String, String> getHeaders() { return headers; }

    // | ================= Unused stubs ================= |
    // These methods are not called by any adapter but are required by the HttpRequest contract

    @Override public Method              getMethod() { return Method.POST; }
    @Override public String              getPath()   { return "/"; }
    @Override public Map<String, String> getArgs()   { return Map.of(); }
}