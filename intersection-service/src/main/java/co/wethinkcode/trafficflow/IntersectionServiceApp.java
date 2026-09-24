package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stage 2: the source of truth for intersection and district validation.
 * Stage 4: publishes a heartbeat so the watchdog can tell whether it is alive.
 *
 * <pre>
 *   GET  /health              "OK"
 *   GET  /intersections       all known intersections (?district=Sandton)
 *   GET  /intersections/{id}  one intersection, 404 when unknown
 *   GET  /districts           district to intersection count
 *   POST /refresh             reload from ingestion-service
 *   GET  /status              load state and broker connection
 * </pre>
 *
 * <p>The heartbeat goes on a <b>queue</b> rather than a topic on purpose. A
 * topic only reaches subscribers connected at the moment of publishing, so a
 * watchdog that was restarting would see silence and could not tell the
 * difference between "no heartbeat" and "I wasn't listening". A queue holds the
 * messages until the watchdog reads them.
 */
public final class IntersectionServiceApp {

    private static final Logger log = LoggerFactory.getLogger(IntersectionServiceApp.class);
    private static final int PORT = 7021;
    private static final String HEARTBEAT_QUEUE = "intersection-heartbeat-queue";
    private static final long HEARTBEAT_SECONDS = 5;

    private IntersectionServiceApp() {
    }

    public static void main(String[] args) {
        IntersectionRepository repository = new IntersectionRepository();
        IngestionClient ingestion = new IngestionClient(IngestionClient.defaultUrl());
        Broker broker = new Broker(Broker.url());
        ObjectMapper mapper = new ObjectMapper();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // ingestion-service may not be up yet; keep trying rather than dying.
        scheduler.scheduleWithFixedDelay(() -> {
            if (repository.size() > 0) {
                return;
            }
            try {
                repository.replaceAll(ingestion.fetchIntersections());
                log.info("Loaded {} intersections from ingestion-service", repository.size());
            } catch (Exception e) {
                log.warn("Waiting for ingestion-service: {}", e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                String payload = mapper.writeValueAsString(Map.of(
                        "service", "intersection-service",
                        "intersections", repository.size(),
                        "at", Instant.now().toString()));
                broker.publishToQueue(HEARTBEAT_QUEUE, payload);
            } catch (Exception e) {
                log.warn("Heartbeat not sent: {}", e.getMessage());
            }
        }, 2, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/intersections", ctx -> {
            String district = ctx.queryParam("district");
            ctx.json(district == null || district.isBlank()
                    ? repository.all()
                    : repository.inDistrict(district));
        });

        app.get("/intersections/{id}", ctx -> repository.find(ctx.pathParam("id"))
                .ifPresentOrElse(ctx::json, () -> ctx.status(404)
                        .json(Map.of("error", "Unknown intersection: " + ctx.pathParam("id")))));

        app.get("/districts", ctx -> ctx.json(repository.districtCounts()));

        app.post("/refresh", ctx -> {
            try {
                repository.replaceAll(ingestion.fetchIntersections());
                ctx.json(Map.of("loaded", repository.size()));
            } catch (Exception e) {
                ctx.status(503).json(Map.of("error", "ingestion-service unavailable: " + e.getMessage()));
            }
        });

        app.get("/status", ctx -> ctx.json(Map.of(
                "service", "intersection-service",
                "intersections", repository.size(),
                "brokerConnected", broker.isConnected(),
                "heartbeatQueue", HEARTBEAT_QUEUE)));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            broker.close();
            app.stop();
        }, "intersection-shutdown"));

        log.info("intersection-service listening on http://localhost:{}", PORT);
    }
}
