package edge_control.translation.adapter;

import edge_control.logger.EdgeControlLogger;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HttpForgery {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    private static final String DEVICE_CERT_PATH  = "/usr/local/share/ca-certificates/nuc8.crt";
    private static final String BACKEND_CERT_PATH = "/usr/local/share/ca-certificates/nuc1.crt";

    private static final ExecutorService httpExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    // Plain HTTP shared client
    private static final HttpClient sharedHttpClient = HttpClient.newBuilder()
            .executor(httpExecutor)
            .build();

    // TLS clients cached by cert path — built once per cert, reused across all requests
    private static final ConcurrentHashMap<String, HttpClient> tlsClientCache =
            new ConcurrentHashMap<>();

    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("accept-charset", "accept-encoding", "access-control-request-headers",
                    "access-control-request-method", "connection", "content-length", "cookie",
                    "cookie2", "date", "dnt", "expect", "host", "keep-alive", "origin",
                    "referer", "te", "trailer", "transfer-encoding", "upgrade", "via");

    public record DeviceResponse(int statusCode, String body) {}

    // ── Request ───────────────────────────────────────────────────────────────

    public static CompletableFuture<DeviceResponse> doRequestAsync(
            String method, String endpoint, String jsonBody,
            Map<String, String> headers,
            Duration connectTimeout,
            Duration requestTimeout) {

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("X-Forwarded-Host", "EdgeControl/Protocol-Translation");

        if (headers != null) {
            headers.entrySet().stream()
                    .filter(e -> !RESTRICTED_HEADERS.contains(e.getKey().toLowerCase()))
                    .forEach(e -> requestBuilder.header(e.getKey(), e.getValue()));
        }

        if (requestTimeout != null && requestTimeout.toSeconds() > 0) {
            requestBuilder.timeout(requestTimeout);
        }

        HttpRequest request = requestBuilder
                .method(method.toUpperCase(),
                        jsonBody != null && !jsonBody.isEmpty()
                                ? HttpRequest.BodyPublishers.ofString(jsonBody)
                                : HttpRequest.BodyPublishers.noBody())
                .build();

        HttpClient client = resolveClient(endpoint, connectTimeout);

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new DeviceResponse(r.statusCode(), r.body()));
    }

    // ── Client resolution ─────────────────────────────────────────────────────

    private static HttpClient resolveClient(String endpoint, Duration connectTimeout) {
        boolean isTls      = endpoint.toLowerCase().startsWith("https://");
        boolean hasTimeout = connectTimeout != null && connectTimeout.toSeconds() > 0;

        if (!isTls) {
            // Plain HTTP — shared client, or per-request if connectTimeout is set
            if (!hasTimeout) return sharedHttpClient;
            return HttpClient.newBuilder()
                    .executor(httpExecutor)
                    .connectTimeout(connectTimeout)
                    .build();
        }

        // HTTPS — resolve which cert to trust based on the endpoint
        String certPath = resolveCertPath(endpoint);

        if (!hasTimeout) {
            // No timeout — use the cached TLS client for this cert
            return tlsClientCache.computeIfAbsent(certPath, HttpForgery::buildTlsClient);
        }

        // connectTimeout specified — build a per-request TLS client
        // (can't cache these since the timeout varies)
        return buildTlsClient(certPath, connectTimeout);
    }

    /**
     * Resolves which certificate to trust based on the endpoint URL.
     * TODO: make this configurable per device once POC is validated.
     */
    private static String resolveCertPath(String endpoint) {
        if (endpoint.contains("nuc8")) return DEVICE_CERT_PATH;
        if (endpoint.contains("nuc1")) return BACKEND_CERT_PATH;
        // Default — fall through to JVM trust store (returns null → no custom SSLContext)
        return null;
    }

    // ── TLS client builders ───────────────────────────────────────────────────

    /** Builds a cached TLS client (no connectTimeout). */
    private static HttpClient buildTlsClient(String certPath) {
        return buildTlsClient(certPath, null);
    }

    /** Builds a TLS client with optional connectTimeout. */
    private static HttpClient buildTlsClient(String certPath, Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(httpExecutor);

        if (connectTimeout != null && connectTimeout.toSeconds() > 0) {
            builder.connectTimeout(connectTimeout);
        }

        if (certPath != null) {
            try {
                SSLContext sslContext = TlsHelper.buildSslContext(certPath);
                builder.sslContext(sslContext);
            } catch (Exception e) {
                logger.log("Could not load cert from " + certPath
                        + " — falling back to JVM default trust store: " + e.getMessage());
            }
        }

        return builder.build();
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    public static void shutdown() {
        httpExecutor.shutdown();
        try {
            if (!httpExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}