package co.wethinkcode.trafficflow;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stage 4: alerts when intersection-service stops sending heartbeats.
 *
 * <pre>
 *   GET /health   "OK"
 *   GET /status   UP, DOWN or UNKNOWN, with the last heartbeat
 *   GET /alerts   every state change, newest first
 * </pre>
 *
 * <p>The timeout is three missed heartbeats rather than one. A single dropped
 * message — a GC pause, a blip on the network — is not an outage, and a
 * watchdog that cries wolf gets ignored, which is worse than no watchdog.
 */
public final class IntersectionWatchdogApp {

    private static final Logger log = LoggerFactory.getLogger(IntersectionWatchdogApp.class);
    private static final int PORT = 7024;
    static final String HEARTBEAT_QUEUE = "intersection-heartbeat-queue";
    private static final Duration TIMEOUT = Duration.ofSeconds(16);

    private IntersectionWatchdogApp() {
    }

    public static void main(String[] args) {
        HeartbeatMonitor monitor = new HeartbeatMonitor("intersection-service", TIMEOUT);
        Broker broker = new Broker(Broker.url());

        broker.subscribeToQueue(HEARTBEAT_QUEUE, payload ->
                monitor.onHeartbeat(payload, Instant.now()).ifPresent(IntersectionWatchdogApp::announce));

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "watchdog");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
                () -> monitor.check(Instant.now()).ifPresent(IntersectionWatchdogApp::announce),
                5, 5, TimeUnit.SECONDS);

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/status", ctx -> {
            Map<String, Object> status = monitor.status(Instant.now());
            status.put("brokerConnected", broker.isConnected());
            status.put("queue", HEARTBEAT_QUEUE);
            ctx.json(status);
        });
        app.get("/alerts", ctx -> ctx.json(monitor.alerts()));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            broker.close();
            app.stop();
        }, "watchdog-shutdown"));

        log.info("intersection-watchdog listening on http://localhost:{}, watching {}", PORT, HEARTBEAT_QUEUE);
    }

    private static void announce(HeartbeatMonitor.Alert alert) {
        if (alert.state() == HeartbeatMonitor.State.DOWN) {
            log.error("ALERT: {}", alert.detail());
        } else {
            log.info("RECOVERED: {}", alert.detail());
        }
    }
}
