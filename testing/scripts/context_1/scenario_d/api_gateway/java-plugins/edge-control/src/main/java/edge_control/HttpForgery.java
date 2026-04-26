package edge_control;

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

/**
 * Utility class for sending async HTTP/HTTPS requests to downstream devices and backends.
 *
 * Manages a shared plain-HTTP client and a cache of TLS clients keyed by certificate path.
 * TLS is enabled automatically when the endpoint starts with "https://".
 * The correct certificate is resolved from the endpoint URL (see resolveCertPath).
 *
 * All requests are sent asynchronously using virtual threads.
 */
public class HttpForgery {

    // private static final EdgeControlLogger logger = EdgeControlLogger.getInstance();

    // TODO: make cert paths configurable per device once POC is validated
    private static final String DEVICE_CERT_PATH  = "/usr/local/share/ca-certificates/nuc8.crt";
    private static final String BACKEND_CERT_PATH = "/usr/local/share/ca-certificates/nuc1.crt";

    // Shared executor using virtual threads for non-blocking async I/O
    private static final ExecutorService httpExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    // Shared plain HTTP client, reused across all non-TLS requests
    private static final HttpClient sharedHttpClient = HttpClient.newBuilder()
            .executor(httpExecutor)
            .build();

    // TLS clients cached by cert path, built once per cert and reused
    private static final ConcurrentHashMap<String, HttpClient> tlsClientCache =
            new ConcurrentHashMap<>();

    // Headers that must not be forwarded as they are controlled by the HTTP layer
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("accept-charset", "accept-encoding", "access-control-request-headers",
                    "access-control-request-method", "connection", "content-length", "cookie",
                    "cookie2", "date", "dnt", "expect", "host", "keep-alive", "origin",
                    "referer", "te", "trailer", "transfer-encoding", "upgrade", "via");

    /**
     * Wraps the status code and body of a downstream response.
     *
     * @param statusCode HTTP status code returned by the downstream service
     * @param body       Response body as a string
     */
    public record DeviceResponse(int statusCode, String body) {}

    // | ================= Request ================= |

    /**
     * Sends an async HTTP or HTTPS request to the given endpoint.
     *
     * TLS is enabled automatically when the endpoint starts with "https://".
     * The certificate used for trust verification is resolved from the endpoint URL.
     * Restricted headers are filtered out before forwarding.
     *
     * @param method         HTTP method (GET, POST, etc.)
     * @param endpoint       Full URL of the downstream service
     * @param jsonBody       Request body as a JSON string, or null/empty for no body
     * @param headers        Headers to forward (restricted headers are skipped)
     * @param connectTimeout Maximum time to establish the TCP connection, or null for no limit
     * @param requestTimeout Maximum time to wait for a response, or null for no limit
     * @return CompletableFuture resolving to the downstream response
     */
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

    // | ================= Client resolution ================= |

    /**
     * Picks the appropriate HttpClient based on the endpoint scheme and connect timeout.
     *
     * Plain HTTP uses the shared client unless a connect timeout is set, in which case
     * a dedicated client is built. HTTPS clients are cached by cert path and reused,
     * unless a connect timeout is set (since timeouts vary and cannot be shared).
     *
     * @param endpoint       The target URL, used to detect TLS and resolve the cert
     * @param connectTimeout Optional TCP connect timeout
     * @return HttpClient configured for the given endpoint
     */
    private static HttpClient resolveClient(String endpoint, Duration connectTimeout) {
        boolean isTls      = endpoint.toLowerCase().startsWith("https://");
        boolean hasTimeout = connectTimeout != null && connectTimeout.toSeconds() > 0;

        if (!isTls) {
            if (!hasTimeout) return sharedHttpClient;
            // Connect timeout requires a dedicated client since it cannot be changed after build
            return HttpClient.newBuilder()
                    .executor(httpExecutor)
                    .connectTimeout(connectTimeout)
                    .build();
        }

        String certPath = resolveCertPath(endpoint);

        if (!hasTimeout) {
            if (certPath == null) {
                // No matching cert, fall back to JVM default trust store without caching
                // (null is not a valid ConcurrentHashMap key)
                return buildTlsClient(null, null);
            }
            // Cache by cert path so each cert only builds one client
            return tlsClientCache.computeIfAbsent(certPath, HttpForgery::buildTlsClient);
        }

        // Per-request client needed when a connect timeout is specified
        return buildTlsClient(certPath, connectTimeout);
    }

    /**
     * Resolves the certificate path to trust based on the endpoint URL.
     * Returns null if no matching cert is found, falling back to the JVM default trust store.
     *
     * TODO: make this configurable per device once POC is validated.
     *
     * @param endpoint The target URL
     * @return Path to the .crt file to trust, or null to use the JVM default
     */
    private static String resolveCertPath(String endpoint) {
        if (endpoint.contains("nuc8")) return DEVICE_CERT_PATH;
        if (endpoint.contains("nuc1")) return BACKEND_CERT_PATH;
        return null;
    }

    // | ================= TLS client builders ================= |

    /**
     * Builds a TLS HttpClient without a connect timeout.
     * Used as a method reference for the tlsClientCache.
     *
     * @param certPath Path to the .crt file to trust, or null for JVM defaults
     * @return HttpClient with TLS configured
     */
    private static HttpClient buildTlsClient(String certPath) {
        return buildTlsClient(certPath, null);
    }

    /**
     * Builds a TLS HttpClient, optionally with a connect timeout.
     * If certPath is null, the JVM default trust store is used.
     * If the cert file cannot be loaded, falls back to JVM defaults with a warning.
     *
     * @param certPath       Path to the .crt file to trust, or null for JVM defaults
     * @param connectTimeout Optional TCP connect timeout
     * @return HttpClient with TLS configured
     */
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
                //logger.warn("Could not load cert from " + certPath
                //        + " - falling back to JVM default trust store: " + e.getMessage());
            }
        }

        return builder.build();
    }

    // | ================= Shutdown ================= |

    /**
     * Gracefully shuts down the shared HTTP executor.
     * Waits up to 10 seconds for in-flight requests to complete before forcing shutdown.
     */
    public static void shutdown() {
        httpExecutor.shutdown();
        try {
            if (!httpExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            // Restore the interrupted status so callers can handle it if needed
            Thread.currentThread().interrupt();
        }
    }
}