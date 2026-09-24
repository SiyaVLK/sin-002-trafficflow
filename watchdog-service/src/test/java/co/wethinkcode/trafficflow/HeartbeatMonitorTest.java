package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatMonitorTest {

    private final Instant start = Instant.parse("2026-09-24T18:00:00Z");
    private final HeartbeatMonitor monitor =
            new HeartbeatMonitor("intersection-service", Duration.ofSeconds(16));

    private static final String BEAT = "{\"service\":\"intersection-service\",\"intersections\":30}";

    @Test
    @DisplayName("Stays UNKNOWN until the first heartbeat, rather than alerting on start-up")
    void startsUnknown() {
        assertEquals(HeartbeatMonitor.State.UNKNOWN, monitor.state());
        assertTrue(monitor.check(start.plusSeconds(60)).isEmpty());
        assertEquals(0, monitor.alerts().size());
    }

    @Test
    @DisplayName("The first heartbeat marks the service alive")
    void firstHeartbeatIsAnAlert() {
        assertTrue(monitor.onHeartbeat(BEAT, start).isPresent());
        assertEquals(HeartbeatMonitor.State.UP, monitor.state());
    }

    @Test
    @DisplayName("Steady heartbeats produce no further noise")
    void steadyHeartbeatsAreQuiet() {
        monitor.onHeartbeat(BEAT, start);
        assertTrue(monitor.onHeartbeat(BEAT, start.plusSeconds(5)).isEmpty());
        assertTrue(monitor.onHeartbeat(BEAT, start.plusSeconds(10)).isEmpty());
        assertEquals(1, monitor.alerts().size());
    }

    @Test
    @DisplayName("One missed heartbeat is not an outage")
    void singleMissedBeatIsTolerated() {
        monitor.onHeartbeat(BEAT, start);

        assertTrue(monitor.check(start.plusSeconds(10)).isEmpty());
        assertEquals(HeartbeatMonitor.State.UP, monitor.state());
    }

    @Test
    @DisplayName("Sustained silence raises exactly one DOWN alert")
    void silenceRaisesOneAlert() {
        monitor.onHeartbeat(BEAT, start);

        assertTrue(monitor.check(start.plusSeconds(20)).isPresent());
        assertTrue(monitor.check(start.plusSeconds(25)).isEmpty());
        assertTrue(monitor.check(start.plusSeconds(60)).isEmpty());

        assertEquals(HeartbeatMonitor.State.DOWN, monitor.state());
        assertEquals(2, monitor.alerts().size()); // the initial UP, then DOWN
    }

    @Test
    @DisplayName("A returning heartbeat raises a recovery alert")
    void recoveryIsAnnounced() {
        monitor.onHeartbeat(BEAT, start);
        monitor.check(start.plusSeconds(20));

        HeartbeatMonitor.Alert recovery = monitor.onHeartbeat(BEAT, start.plusSeconds(30)).orElseThrow();

        assertEquals(HeartbeatMonitor.State.UP, recovery.state());
        assertTrue(recovery.detail().contains("again"));
    }

    @Test
    @DisplayName("Status reports how long the silence has lasted")
    void statusReportsSilence() {
        monitor.onHeartbeat(BEAT, start);

        assertEquals(12L, monitor.status(start.plusSeconds(12)).get("secondsSinceLastHeartbeat"));
        assertEquals(1L, monitor.status(start).get("heartbeatsReceived"));
    }

    @Test
    @DisplayName("Alerts are newest first")
    void alertsAreNewestFirst() {
        monitor.onHeartbeat(BEAT, start);
        monitor.check(start.plusSeconds(20));

        assertEquals(HeartbeatMonitor.State.DOWN, monitor.alerts().get(0).state());
        assertFalse(monitor.alerts().isEmpty());
    }
}
