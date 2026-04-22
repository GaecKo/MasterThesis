package edge_control.translation.queuing.model;

import org.bson.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a request that failed to reach a device and is awaiting retry.
 * Stores everything needed to reconstruct and replay the original request,
 * including the callback endpoint to notify once delivery eventually succeeds.
 */
public class QueuedRequest {

    private final String              id;
    private final String              gatewayDeviceId;
    private final String              callbackEndpoint;
    private final String              body;
    private final Map<String, String> headers;
    private final Instant             enqueuedAt;

    // | ================= Constructors ================= |

    /**
     * Creates a new queued request from a failed delivery attempt.
     * A unique ID and enqueue timestamp are assigned automatically.
     *
     * @param gatewayDeviceId  Device the request was destined for
     * @param callbackEndpoint Backend URL to notify on eventual delivery or TTL expiry
     * @param body             Original request body
     * @param headers          Original request headers (defensively copied)
     */
    public QueuedRequest(String gatewayDeviceId, String callbackEndpoint,
                         String body, Map<String, String> headers) {
        this.id               = UUID.randomUUID().toString();
        this.gatewayDeviceId  = gatewayDeviceId;
        this.callbackEndpoint = callbackEndpoint;
        this.body             = body;
        // Defensive copy so mutations to the caller's map don't affect the queued request
        this.headers          = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.enqueuedAt       = Instant.now();
    }

    /**
     * Reconstructs a QueuedRequest from a MongoDB document.
     *
     * @param doc MongoDB document previously produced by toDocument()
     */
    public QueuedRequest(Document doc) {
        this.id               = doc.getString("id");
        this.gatewayDeviceId  = doc.getString("gatewayDeviceId");
        this.callbackEndpoint = doc.getString("callbackEndpoint");
        this.body             = doc.getString("body");
        this.enqueuedAt       = Instant.parse(doc.getString("enqueuedAt"));

        this.headers = new HashMap<>();
        Document headersDoc = doc.get("headers", Document.class);
        if (headersDoc != null) {
            headersDoc.forEach((k, v) -> headers.put(k, v.toString()));
        }
    }

    // | ================= Serialisation ================= |

    /**
     * Serialises this request to a MongoDB document for persistence.
     *
     * @return Document ready to insert or replace in the queuedRequests collection
     */
    public Document toDocument() {
        Document doc = new Document();
        doc.put("id",               id);
        doc.put("gatewayDeviceId",  gatewayDeviceId);
        doc.put("callbackEndpoint", callbackEndpoint);
        doc.put("body",             body);
        doc.put("headers",          new Document(headers));
        doc.put("enqueuedAt",       enqueuedAt.toString());
        return doc;
    }

    // | ================= Getters ================= |

    public String              getId()                { return id; }
    public String              getGatewayDeviceId()   { return gatewayDeviceId; }
    public String              getCallbackEndpoint()  { return callbackEndpoint; }
    public String              getBody()              { return body; }
    public Map<String, String> getHeaders()           { return headers; }
    public Instant             getEnqueuedAt()        { return enqueuedAt; }
}