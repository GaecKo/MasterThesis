package edge_control.translation.queuing.model;

import org.bson.Document;
import org.json.JSONObject;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a request that failed to reach a device and is awaiting retry.
 * Stores everything needed to reconstruct and replay the original request.
 */
public class QueuedRequest {

    private final String              id;
    private final String              gatewayDeviceId;
    private final String              body;
    private final Map<String, String> headers;
    private final Instant             enqueuedAt;

    // ── Construct from an incoming failed request ─────────────────────────────

    public QueuedRequest(String gatewayDeviceId, String body, Map<String, String> headers) {
        this.id              = UUID.randomUUID().toString();
        this.gatewayDeviceId = gatewayDeviceId;
        this.body            = body;
        this.headers         = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.enqueuedAt      = Instant.now();
    }

    // ── Reconstruct from MongoDB document ─────────────────────────────────────

    public QueuedRequest(Document doc) {
        this.id              = doc.getString("id");
        this.gatewayDeviceId = doc.getString("gatewayDeviceId");
        this.body            = doc.getString("body");
        this.enqueuedAt      = Instant.parse(doc.getString("enqueuedAt"));

        this.headers = new HashMap<>();
        Document headersDoc = doc.get("headers", Document.class);
        if (headersDoc != null) {
            headersDoc.forEach((k, v) -> headers.put(k, v.toString()));
        }
    }

    // ── Serialise to MongoDB document ─────────────────────────────────────────

    public Document toDocument() {
        Document doc = new Document();
        doc.put("id",              id);
        doc.put("gatewayDeviceId", gatewayDeviceId);
        doc.put("body",            body);
        doc.put("headers",         new Document(headers));
        doc.put("enqueuedAt",      enqueuedAt.toString());
        return doc;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String              getId()               { return id; }
    public String              getGatewayDeviceId()  { return gatewayDeviceId; }
    public String              getBody()             { return body; }
    public Map<String, String> getHeaders()          { return headers; }
    public Instant             getEnqueuedAt()       { return enqueuedAt; }
}
