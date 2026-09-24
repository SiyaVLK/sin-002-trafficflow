package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What routing-service believes the congestion level is, and where that belief
 * came from.
 *
 * <p>Stage 3 replaces polling with a topic subscription, but "subscribed" is not
 * the same as "informed": a subscriber that has just started has missed every
 * message sent before it connected. So the feed is primed once over REST, and
 * from then on the topic keeps it current.
 *
 * <p>Messages carry a version, and an update is ignored unless its version is
 * newer than what we already hold. That makes the feed safe against duplicate
 * delivery and against a delayed message arriving after a newer one.
 */
public class CongestionFeed {

    private static final Logger log = LoggerFactory.getLogger(CongestionFeed.class);

    public record Reading(int level, long version, String source, String updatedAt) {
    }

    /** Matches the JSON published by congestion-service. */
    private record Snapshot(int level, String reason, long version, String updatedAt) {
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile Reading current = new Reading(0, -1, "default", null);

    public Reading current() {
        return current;
    }

    /** True once a real value has arrived from either source. */
    public boolean primed() {
        return current.version() >= 0;
    }

    /** Called for every message on the congestion topic. */
    public synchronized void onMessage(String payload) {
        try {
            Snapshot snapshot = mapper.readValue(payload, Snapshot.class);
            apply(snapshot.level(), snapshot.version(), snapshot.updatedAt(), "topic");
        } catch (Exception e) {
            log.warn("Ignoring unreadable congestion message: {}", e.getMessage());
        }
    }

    /** Called when the level is fetched over REST, at start-up or as a fallback. */
    public synchronized void onRestReading(int level, long version, String updatedAt) {
        apply(level, version, updatedAt, "rest");
    }

    private void apply(int level, long version, String updatedAt, String source) {
        if (version < current.version()) {
            log.info("Ignoring congestion v{} from {}, already at v{}", version, source, current.version());
            return;
        }
        current = new Reading(level, version, source, updatedAt);
        log.info("Congestion level is now {} (v{}, via {})", level, version, source);
    }
}
