package co.wethinkcode.trafficflow;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stage 2: estimates travel time between two intersections, validating both
 * against intersection-service and reading the congestion level.
 * Stage 3: the congestion level now arrives on a topic instead of being polled.
 *
 * <pre>
 *   GET /health                          "OK"
 *   GET /route?from=INT-001&to=INT-010   distance, congestion and ETA
 *   GET /congestion                      what routing believes, and its source
 *   GET /status                          broker connection and upstream URLs
 * </pre>
 *
 * <p>What Stage 3 actually buys: before it, every route request either polled
 * congestion-service or served a stale cached number. After it, congestion
 * pushes changes as they happen, so routing has the current level without
 * generating any traffic, and a congestion-service restart no longer makes
 * routing fail — it just keeps using the last level it was told.
 */
public final class RoutingServiceApp {

    private static final Logger log = LoggerFactory.getLogger(RoutingServiceApp.class);
    private static final int PORT = 7023;
    static final String CONGESTION_TOPIC = "congestion-events-topic";

    private RoutingServiceApp() {
    }

    public static void main(String[] args) {
        CongestionFeed feed = new CongestionFeed();
        UpstreamClient upstream = new UpstreamClient(
                UpstreamClient.intersectionsUrlFromEnv(), UpstreamClient.congestionUrlFromEnv());
        Broker broker = new Broker(Broker.url());

        // Stage 3: the topic keeps the level current from here on.
        broker.subscribeToTopic(CONGESTION_TOPIC, feed::onMessage);

        // A subscriber missed everything published before it connected, so ask
        // once over REST. Retried until it succeeds, in case congestion-service
        // starts after this one.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            if (feed.primed()) {
                return;
            }
            try {
                upstream.primeCongestion(feed);
            } catch (Exception e) {
                log.warn("Waiting for congestion-service: {}", e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/congestion", ctx -> ctx.json(feed.current()));

        app.get("/route", ctx -> {
            String fromId = ctx.queryParam("from");
            String toId = ctx.queryParam("to");
            if (fromId == null || toId == null || fromId.isBlank() || toId.isBlank()) {
                ctx.status(400).json(Map.of("error", "from and to are required, e.g. /route?from=INT-001&to=INT-010"));
                return;
            }

            Optional<Intersection> from;
            Optional<Intersection> to;
            try {
                from = upstream.findIntersection(fromId);
                to = upstream.findIntersection(toId);
            } catch (Exception e) {
                // Routing cannot invent an answer it has no data for.
                ctx.status(503).json(Map.of("error", "intersection-service unavailable: " + e.getMessage()));
                return;
            }

            if (from.isEmpty() || to.isEmpty()) {
                ctx.status(404).json(Map.of("error",
                        "Unknown intersection: " + (from.isEmpty() ? fromId : toId)));
                return;
            }

            CongestionFeed.Reading reading = feed.current();
            RouteEstimator.Estimate estimate = RouteEstimator.estimate(from.get(), to.get(), reading.level());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("from", from.get());
            response.put("to", to.get());
            response.put("distanceKm", estimate.distanceKm());
            response.put("distanceEstimated", estimate.distanceEstimated());
            response.put("congestionLevel", reading.level());
            response.put("congestionSource", reading.source());
            response.put("congestionFactor", estimate.congestionFactor());
            response.put("estimatedMinutes", estimate.estimatedMinutes());
            ctx.json(response);
        });

        app.get("/status", ctx -> ctx.json(Map.of(
                "service", "routing-service",
                "brokerConnected", broker.isConnected(),
                "topic", CONGESTION_TOPIC,
                "congestion", feed.current())));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            broker.close();
            app.stop();
        }, "routing-shutdown"));

        log.info("routing-service listening on http://localhost:{}, subscribed to {}", PORT, CONGESTION_TOPIC);
    }
}
