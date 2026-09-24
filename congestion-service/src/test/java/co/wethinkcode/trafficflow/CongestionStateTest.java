package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CongestionStateTest {

    private final Instant now = Instant.parse("2026-09-24T18:00:00Z");
    private final CongestionState state = new CongestionState(now);

    @Test
    @DisplayName("Starts at level 0 with version 0")
    void startsFree() {
        assertEquals(0, state.current().level());
        assertEquals(0L, state.current().version());
    }

    @Test
    @DisplayName("Each change increments the version")
    void versionsIncrease() {
        state.update(3, "Accident on the M1", now);
        state.update(5, "Peak hour", now);

        assertEquals(5, state.current().level());
        assertEquals(2L, state.current().version());
    }

    @Test
    @DisplayName("Re-posting the same level is not a change and produces no event")
    void repeatedUpdateIsNotAChange() {
        state.update(3, "Accident on the M1", now);

        assertTrue(state.update(3, "Accident on the M1", now).isEmpty());
        assertEquals(1L, state.current().version());
    }

    @Test
    @DisplayName("Levels outside the 0-8 scale are rejected")
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> state.update(9, "too high", now));
        assertThrows(IllegalArgumentException.class, () -> state.update(-1, "too low", now));
        assertEquals(0L, state.current().version());
    }

    @Test
    @DisplayName("Both ends of the scale are valid")
    void acceptsBoundaries() {
        assertTrue(state.update(8, "Gridlock", now).isPresent());
        assertTrue(state.update(0, "Cleared", now).isPresent());
    }

    @Test
    @DisplayName("A blank reason is replaced rather than stored empty")
    void fillsInMissingReason() {
        state.update(2, "  ", now);
        assertEquals("No reason given", state.current().reason());
    }
}
