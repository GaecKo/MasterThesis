package edge_control.translation.queuing.synthetic;

import org.apache.apisix.plugin.runner.HttpResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Synthetic HttpResponse used during queue retries.
 * Captures the status code, body, and headers set by the adapter
 * so QueueWorker can inspect the result and forward it to the callback URL.
 */
public class QueueHttpResponse extends HttpResponse {

    private int                       statusCode = 200;
    private String                    body       = "";
    private final Map<String, String> headers    = new HashMap<>();

    public QueueHttpResponse() {
        super(1);
    }

    @Override public void setStatusCode(int statusCode)            { this.statusCode = statusCode; }
    @Override public void setBody(String body)                     { this.body = body; }
    @Override public void setHeader(String name, String value)     { headers.put(name, value); }

    // | ================= Readers for QueueWorker ================= |

    /** @return The status code set by the adapter */
    public int getStatusCode()             { return statusCode; }

    /** @return The response body set by the adapter */
    public String getBody()                { return body; }

    /** @return The headers set by the adapter */
    public Map<String, String> getHeaders(){ return headers; }

    /**
     * @return True if the adapter set a 2xx status code, indicating successful delivery
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}