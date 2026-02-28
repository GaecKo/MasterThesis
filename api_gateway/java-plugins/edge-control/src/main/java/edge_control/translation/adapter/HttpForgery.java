package edge_control.translation.adapter;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

public class HttpForgery {

    public static String doRequest(String method, String endpoint, String jsonBody, Map<String, String> headers) throws IOException, InterruptedException {

        // automatic headers: to not re-use:
        Set<String> restrictedHeaders = Set.of(
                "content-length", "host", "connection"
        );
        restrictedHeaders.forEach(headers::remove);

        // base req
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("X-Forwarded-Host", "EdgeControl/Protocol-Translation");

        // Add all headers
        headers.forEach(builder::header);

        // Build and send request
        HttpRequest request = builder.method(
                method.toUpperCase(),
                jsonBody != null && !jsonBody.isEmpty()
                        ? HttpRequest.BodyPublishers.ofString(jsonBody)
                        : HttpRequest.BodyPublishers.noBody()
        ).build();

        // send response
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());


        return response.body();
    }

}
