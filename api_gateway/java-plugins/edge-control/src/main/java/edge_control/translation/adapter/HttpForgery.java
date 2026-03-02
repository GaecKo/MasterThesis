package edge_control.translation.adapter;

import java.io.IOException;
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

public class HttpForgery {

    private static final HttpClient sharedHttpClient;
    private static final ExecutorService httpExecutor;

    static {
        httpExecutor = Executors.newFixedThreadPool(20);
        sharedHttpClient = HttpClient.newBuilder()
                .executor(httpExecutor)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // Async version - returns CompletableFuture
    public static CompletableFuture<String> doRequestAsync(String method, String endpoint,
                                                           String jsonBody, Map<String, String> headers) {

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("X-Forwarded-Host", "EdgeControl/Protocol-Translation");

        Set<String> restrictedHeaders = Set.of("content-length", "host", "connection");

        if (headers != null) {
            headers.entrySet().stream()
                    .filter(entry -> !restrictedHeaders.contains(entry.getKey().toLowerCase()))
                    .forEach(entry -> requestBuilder.header(entry.getKey(), entry.getValue()));
        }

        HttpRequest request = requestBuilder
                .method(method.toUpperCase(),
                        jsonBody != null && !jsonBody.isEmpty()
                                ? HttpRequest.BodyPublishers.ofString(jsonBody)
                                : HttpRequest.BodyPublishers.noBody())
                .build();


        return sharedHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    public static void shutdown() {
        httpExecutor.shutdown();
    }
}