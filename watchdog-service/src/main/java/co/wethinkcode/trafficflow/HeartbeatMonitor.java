package co.wethinkcode.trafficflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 4: decides whether intersection-service is alive, based only on whether
 * its heartbeats keep arriving.
 *
 * <p>This is the difference between monitoring and polling. Nothing here calls
 * intersection-service; it listens. That matters because a service can accept a
 * TCP connection and answer a health check while being unable to do real work,
 * and because a watchdog that polls adds load to a service that may already be
 * struggling.
 *
 * <p>Absence of a message is the signal, which is why the heartbeat goes on a
 * queue: a queue holds messages while the watchdog restarts, so a gap really
 * means "the sender stopped" rather than "I wasn't listening".
 *
 * <p>Deliberately, the monitor stays UNKNOWN until the first heartbeat ever
 * arrives. Reporting DOWN at start-up, before the other service has had a
 * chance to say anything, would be an alert that means nothing.
 */
public class HeartbeatMonitor {

    public enum State {
        UNKNOWN,
        UP,
        DOWN
    }

    public record Alert(String at, State state, String detail) {
    }

    private static final int ALERT_HISTORY = 50;

    private final String target;
    private final Duration timeout;
    private final Deque<Alert> alerts = new ArrayDeque<>();

    private State state = State.UNKNOWN;
    private Instant lastHeartbeat;
    private String lastPayload;
    private long received;

    public HeartbeatMonitor(String target, Duration timeout) {
        this.target = target;
        this.timeout = timeout;
    }

    /** @return an alert if this heartbeat changed the state */
    public synchronized Optional<Alert> onHeartbeat(String payload, Instant now) {
        lastHeartbeat = now;
        lastPayload = payload;
        received++;

        if (state == State.UP) {
            return Optional.empty();
        }
        State previous = state;
        state = State.UP;
        String detail = previous == State.DOWN
                ? target + " is sending heartbeats again"
                : target + " is alive";
        return Optional.of(record(new Alert(now.toString(), State.UP, detail)));
    }

    /** @return an alert if the silence has now gone on too long */
    public synchronized Optional<Alert> check(Instant now) {
        if (lastHeartbeat == null || state == State.DOWN) {
            return Optional.empty();
        }
        Duration silence = Duration.between(lastHeartbeat, now);
        if (silence.compareTo(timeout) <= 0) {
            return Optional.empty();
        }
        state = State.DOWN;
        return Optional.of(record(new Alert(now.toString(), State.DOWN,
                target + " has not sent a heartbeat for " + silence.toSeconds() + "s")));
    }

    public synchronized State state() {
        return state;
    }

    public synchronized List<Alert> alerts() {
        return new ArrayList<>(alerts);
    }

    public synchronized Map<String, Object> status(Instant now) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("target", target);
        status.put("state", state);
        status.put("heartbeatsReceived", received);
        status.put("timeoutSeconds", timeout.toSeconds());
        status.put("lastHeartbeat", lastHeartbeat == null ? null : lastHeartbeat.toString());
        status.put("secondsSinceLastHeartbeat",
                lastHeartbeat == null ? null : Duration.between(lastHeartbeat, now).toSeconds());
        status.put("lastPayload", lastPayload);
        return status;
    }

    private Alert record(Alert alert) {
        alerts.addFirst(alert);
        while (alerts.size() > ALERT_HISTORY) {
            alerts.removeLast();
        }
        return alert;
    }
}
