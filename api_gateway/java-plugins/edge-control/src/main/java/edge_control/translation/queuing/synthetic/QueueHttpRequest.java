package edge_control.translation.queuing.synthetic;

import io.github.api7.A6.HTTPReqCall.Req;
import org.apache.apisix.plugin.runner.HttpRequest;

import java.util.Map;

/**
 * Synthetic HttpRequest for use during queue retries.
 * Constructed from stored body and headers — adapters see it as a normal request.
 */
public class QueueHttpRequest extends HttpRequest {

    private final String              body;
    private final Map<String, String> headers;

    public QueueHttpRequest(String body, Map<String, String> headers) {
        super(new Req());
        this.body    = body;
        this.headers = headers;
    }

    @Override
    public String getBody() { return body; }

    @Override
    public Map<String, String> getHeaders() { return headers; }

    // ── Unused by adapters — safe stubs ───────────────────────────────────────

    @Override public Method getMethod()      { return Method.POST; }
    @Override public String getPath()        { return "/"; }
    @Override public Map<String, String> getArgs() { return Map.of(); }
}
