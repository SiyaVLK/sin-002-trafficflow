package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 2: serves the current city-wide congestion level over REST.
 * Stage 3: publishes every change to an ActiveMQ topic so routing-service does
 * not have to poll for it.
 *
 * <pre>
 *   GET  /health      "OK"
 *   GET  /congestion  current level, reason and version
 *   POST /congestion  {"level": 0-8, "reason": "..."}
 *   GET  /status      broker connection and topic name
 * </pre>
 *
 * <p>A <b>topic</b> is right here and a queue would be wrong: the congestion
 * level is a broadcast that every interested service should see. A queue
 * delivers each message to exactly one consumer, so a second subscriber would
 * silently receive half the updates.
 *
 * <p>The REST endpoint stays even after Stage 3. A subscriber that has just
 * started has not received a message yet, and needs a way to ask "what is the
 * level right now?" rather than waiting for the next change.
 */
public final class CongestionServiceApp {

    private static final Logger log = LoggerFactory.getLogger(CongestionServiceApp.class);
    private static final int PORT = 7022;
    static final String CONGESTION_TOPIC = "congestion-events-topic";

    private record LevelRequest(Integer level, String reason) {
    }

    private CongestionServiceApp() {
    }

    public static void main(String[] args) {
        CongestionState state = new CongestionState(Instant.now());
        Broker broker = new Broker(Broker.url());
        ObjectMapper mapper = new ObjectMapper();

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/congestion", ctx -> ctx.json(state.current()));

        app.post("/congestion", ctx -> {
            LevelRequest request;
            try {
                request = mapper.readValue(ctx.body(), LevelRequest.class);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Body must be JSON: {\"level\": 0-8, \"reason\": \"...\"}"));
                return;
            }
            if (request == null || request.level() == null) {
                ctx.status(400).json(Map.of("error", "level is required"));
                return;
            }

            Optional<CongestionState.Snapshot> changed;
            try {
                changed = state.update(request.level(), request.reason(), Instant.now());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
                return;
            }

            if (changed.isEmpty()) {
                ctx.json(Map.of("changed", false, "congestion", state.current()));
                return;
            }

            CongestionState.Snapshot snapshot = changed.get();
            boolean published = publish(broker, mapper, snapshot);
            log.info("Congestion level is now {} ({}), version {}{}",
                    snapshot.level(), snapshot.reason(), snapshot.version(),
                    published ? "" : " - NOT published, broker unavailable");

            ctx.status(201).json(Map.of(
                    "changed", true,
                    "published", published,
                    "congestion", snapshot));
        });

        app.get("/status", ctx -> ctx.json(Map.of(
                "service", "congestion-service",
                "brokerConnected", broker.isConnected(),
                "topic", CONGESTION_TOPIC,
                "congestion", state.current())));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            broker.close();
            app.stop();
        }, "congestion-shutdown"));

        log.info("congestion-service listening on http://localhost:{}, publishing to {}", PORT, CONGESTION_TOPIC);
    }

    private static boolean publish(Broker broker, ObjectMapper mapper, CongestionState.Snapshot snapshot) {
        try {
            broker.publishToTopic(CONGESTION_TOPIC, mapper.writeValueAsString(snapshot));
            return true;
        } catch (Exception e) {
            // The level still changed and /congestion still reports it, so a
            // subscriber that asks will get the truth even though the broadcast
            // was lost. Reported in the response rather than hidden.
            log.warn("Could not publish congestion change: {}", e.getMessage());
            return false;
        }
    }
}
