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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HttpForgery {

    private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    // Path is fixed by the Docker volume mount in the APISIX container
    private static final String APISIX_CERT_PATH = "/usr/local/apisix/conf/server.crt";

    private static final ExecutorService httpExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    // Plain HTTP client — shared, no TLS
    private static final HttpClient sharedHttpClient = HttpClient.newBuilder()
            .executor(httpExecutor)
            .build();

    // HTTPS client — built once from the APISIX cert, shared for all TLS requests
    private static final HttpClient sharedTlsHttpClient = buildTlsClient(null);

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

    /**
     * Picks the right HttpClient based on the endpoint scheme and timeout.
     * - https:// → TLS client (shared or per-request with connectTimeout)
     * - http://  → plain client (shared or per-request with connectTimeout)
     * Per-request clients are only built when a connectTimeout is specified,
     * but they always share the thread pool.
     */
    private static HttpClient resolveClient(String endpoint, Duration connectTimeout) {
        boolean isTls = endpoint.toLowerCase().startsWith("https://");
        boolean hasTimeout = connectTimeout != null && connectTimeout.toSeconds() > 0;

        if (!hasTimeout) {
            // No timeout — use the appropriate shared client
            return isTls ? sharedTlsHttpClient : sharedHttpClient;
        }

        // connectTimeout specified — build a per-request client
        return buildTlsClient(isTls ? connectTimeout : null);
    }

    /**
     * Builds an HttpClient.
     * If connectTimeout is non-null, applies it.
     * Always applies the APISIX SSLContext (used even for plain HTTP clients
     * that happen to need a connectTimeout, but SSLContext only activates on https:// URLs).
     */
    private static HttpClient buildTlsClient(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(httpExecutor);

        if (connectTimeout != null && connectTimeout.toSeconds() > 0) {
            builder.connectTimeout(connectTimeout);
        }

        // Load the APISIX cert — if the file isn't present (e.g. local dev without
        // the volume mount), fall back gracefully and log a warning
        try {
            SSLContext sslContext = TlsHelper.buildSslContext(APISIX_CERT_PATH);
            builder.sslContext(sslContext);
        } catch (Exception e) {
            logger.info("Could not load APISIX cert from " + APISIX_CERT_PATH
                    + " — HTTPS requests will use JVM default trust store: " + e.getMessage());
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