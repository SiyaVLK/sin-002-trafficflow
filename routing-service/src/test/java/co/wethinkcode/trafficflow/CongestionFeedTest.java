package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CongestionFeedTest {

    private final CongestionFeed feed = new CongestionFeed();

    private static String message(int level, long version) {
        return """
                {"level":%d,"reason":"Peak hour","version":%d,"updatedAt":"2026-09-24T18:00:00Z"}
                """.formatted(level, version);
    }

    @Test
    @DisplayName("Starts unprimed at level 0 so a route still gets an answer")
    void startsUnprimed() {
        assertFalse(feed.primed());
        assertEquals(0, feed.current().level());
        assertEquals("default", feed.current().source());
    }

    @Test
    @DisplayName("A topic message updates the level and records the source")
    void topicMessageUpdatesLevel() {
        feed.onMessage(message(5, 3));

        assertEquals(5, feed.current().level());
        assertEquals("topic", feed.current().source());
        assertTrue(feed.primed());
    }

    @Test
    @DisplayName("A redelivered message does not move the level backwards")
    void ignoresStaleVersions() {
        feed.onMessage(message(5, 3));
        feed.onMessage(message(1, 2));

        assertEquals(5, feed.current().level());
        assertEquals(3L, feed.current().version());
    }

    @Test
    @DisplayName("A duplicate of the newest message is harmless")
    void duplicateIsHarmless() {
        feed.onMessage(message(5, 3));
        feed.onMessage(message(5, 3));

        assertEquals(5, feed.current().level());
        assertEquals(3L, feed.current().version());
    }

    @Test
    @DisplayName("An unreadable message is ignored rather than crashing the subscriber")
    void ignoresGarbage() {
        feed.onMessage("not json at all");

        assertFalse(feed.primed());
        assertEquals(0, feed.current().level());
    }

    @Test
    @DisplayName("The REST reading primes the feed before any message arrives")
    void restPrimesTheFeed() {
        feed.onRestReading(4, 7, "2026-09-24T18:00:00Z");

        assertTrue(feed.primed());
        assertEquals(4, feed.current().level());
        assertEquals("rest", feed.current().source());
    }

    @Test
    @DisplayName("A later topic message overrides the primed REST value")
    void topicTakesOverAfterPriming() {
        feed.onRestReading(4, 7, "2026-09-24T18:00:00Z");
        feed.onMessage(message(6, 8));

        assertEquals(6, feed.current().level());
        assertEquals("topic", feed.current().source());
    }
}
