package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Stage 2 integration: validates intersections against intersection-service and
 * primes the congestion level from congestion-service.
 *
 * <p>404 is an answer, not a failure. An unknown intersection returns empty so
 * the caller can return a clean 404 of its own; only an unreachable or broken
 * service throws.
 */
public class UpstreamClient {

    private final String intersectionsUrl;
    private final String congestionUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public UpstreamClient(String intersectionsUrl, String congestionUrl) {
        this.intersectionsUrl = intersectionsUrl.replaceAll("/+$", "");
        this.congestionUrl = congestionUrl.replaceAll("/+$", "");
    }

    public static String intersectionsUrlFromEnv() {
        String configured = System.getenv("INTERSECTION_URL");
        return configured == null || configured.isBlank() ? "http://localhost:7021" : configured;
    }

    public static String congestionUrlFromEnv() {
        String configured = System.getenv("CONGESTION_URL");
        return configured == null || configured.isBlank() ? "http://localhost:7022" : configured;
    }

    /** @return the intersection, or empty if intersection-service says it does not exist */
    public Optional<Intersection> findIntersection(String id) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> response = get(intersectionsUrl + "/intersections/" + encoded);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("intersection-service returned HTTP " + response.statusCode());
        }
        return Optional.of(mapper.readValue(response.body(), Intersection.class));
    }

    /** Primes the feed at start-up, before any topic message has arrived. */
    public void primeCongestion(CongestionFeed feed) throws IOException, InterruptedException {
        HttpResponse<String> response = get(congestionUrl + "/congestion");
        if (response.statusCode() != 200) {
            throw new IOException("congestion-service returned HTTP " + response.statusCode());
        }
        JsonNode node = mapper.readTree(response.body());
        feed.onRestReading(
                node.path("level").asInt(0),
                node.path("version").asLong(0),
                node.path("updatedAt").asText(null));
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
