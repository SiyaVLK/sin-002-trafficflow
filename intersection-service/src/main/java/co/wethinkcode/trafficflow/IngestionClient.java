package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Stage 2: fetches the cleaned records from ingestion-service over HTTP.
 *
 * <p>Both timeouts are explicit. The JDK default is to wait forever, so one
 * hung dependency would eventually tie up every thread in this service too. A
 * timeout turns a hang into a failure, and a failure is something the caller
 * can actually handle.
 *
 * <p>Unknown JSON properties are ignored, so ingestion-service can add a field
 * without this service failing to parse the response.
 */
public class IngestionClient {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public IngestionClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public static String defaultUrl() {
        String configured = System.getenv("INGESTION_URL");
        return configured == null || configured.isBlank() ? "http://localhost:7020" : configured;
    }

    public List<Intersection> fetchIntersections() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/intersections"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("ingestion-service returned HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), new TypeReference<List<Intersection>>() { });
    }
}
