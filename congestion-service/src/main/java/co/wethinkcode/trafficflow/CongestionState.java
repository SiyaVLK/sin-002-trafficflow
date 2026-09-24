package co.wethinkcode.trafficflow;

import java.time.Instant;
import java.util.Optional;

/**
 * The city-wide congestion level, on the 0-8 scale the brief defines.
 *
 * <p>Every change increments a version. Consumers use it to ignore duplicate
 * or out-of-order messages: with at-least-once delivery, the same update can
 * arrive twice, and a slow message can arrive after a newer one.
 *
 * <p>Re-posting the level the city is already on is not a change, so it does
 * not bump the version and does not produce an event. Without that check, a
 * poller re-sending the current level every minute would flood subscribers.
 */
public class CongestionState {

    public static final int MIN_LEVEL = 0;
    public static final int MAX_LEVEL = 8;

    public record Snapshot(int level, String reason, long version, String updatedAt) {
    }

    private Snapshot current;

    public CongestionState(Instant startedAt) {
        this.current = new Snapshot(0, "Free flow", 0, startedAt.toString());
    }

    public synchronized Snapshot current() {
        return current;
    }

    /**
     * @return the new snapshot, or empty when nothing changed
     * @throws IllegalArgumentException if the level is outside 0-8
     */
    public synchronized Optional<Snapshot> update(int level, String reason, Instant now) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Congestion level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", was " + level);
        }
        String cleaned = reason == null || reason.isBlank() ? "No reason given" : reason.strip();
        if (current.level() == level && current.reason().equals(cleaned)) {
            return Optional.empty();
        }
        current = new Snapshot(level, cleaned, current.version() + 1, now.toString());
        return Optional.of(current);
    }
}
