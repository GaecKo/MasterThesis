package edge_control.translation.adapter;

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

    private static final HttpClient sharedHttpClient;
    private static final ExecutorService httpExecutor;

    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("content-length", "host", "connection");

    public record DeviceResponse(int statusCode, String body) {}

    static {
        httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
        sharedHttpClient = HttpClient.newBuilder()
                .executor(httpExecutor)
                .build();
    }

    public static CompletableFuture<DeviceResponse> doRequestAsync(
            String method, String endpoint, String jsonBody,
            Map<String, String> headers,
            Duration connectTimeout,   // null or zero = infinite
            Duration requestTimeout) { // null or zero = infinite

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("X-Forwarded-Host", "EdgeControl/Protocol-Translation");

        // Forward headers, skipping restricted ones
        if (headers != null) {
            headers.entrySet().stream()
                    .filter(e -> !RESTRICTED_HEADERS.contains(e.getKey().toLowerCase()))
                    .forEach(e -> requestBuilder.header(e.getKey(), e.getValue()));
        }

        // Apply request timeout if specified 
        if (requestTimeout != null && requestTimeout.toSeconds() > 0) {
            requestBuilder.timeout(requestTimeout);
        }

        // Build the request
        HttpRequest request = requestBuilder
                .method(method.toUpperCase(),
                        jsonBody != null && !jsonBody.isEmpty()
                                ? HttpRequest.BodyPublishers.ofString(jsonBody)
                                : HttpRequest.BodyPublishers.noBody())
                .build();

        // Use a per-request client only if a connect timeout is specified
        HttpClient client;
        if (connectTimeout != null && connectTimeout.toSeconds() > 0) {
            client = HttpClient.newBuilder()
                    .executor(httpExecutor) // still share the thread pool
                    .connectTimeout(connectTimeout)
                    .build();
        } else {
            client = sharedHttpClient;
        }

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new DeviceResponse(r.statusCode(), r.body()));
    }

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