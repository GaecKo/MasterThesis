package edge_control.translation.queuing.synthetic;

import org.apache.apisix.plugin.runner.HttpResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Synthetic HttpResponse for use during queue retries.
 * Captures statusCode, body, and headers set by the adapter
 * so the QueueWorker can inspect and forward the result.
 */
public class QueueHttpResponse extends HttpResponse {

    private int                    statusCode = 200;
    private String                 body       = "";
    private final Map<String, String> headers = new HashMap<>();

    public QueueHttpResponse() {
        super(1);
    }

    @Override
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    @Override
    public void setBody(String body) { this.body = body; }

    @Override
    public void setHeader(String name, String value) { headers.put(name, value); }

    // ── Readers for QueueWorker ───────────────────────────────────────────────

    public int                    getStatusCode() { return statusCode; }
    public String                 getBody()       { return body; }
    public Map<String, String>    getHeaders()    { return headers; }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}