package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteEstimatorTest {

    private static final Intersection SANDTON =
            new Intersection("INT-009", "Rivonia Rd & Katherine St", "Sandton", -26.106, 28.058);
    private static final Intersection GRAYSTON =
            new Intersection("INT-010", "Grayston Dr & Sandton Dr", "Sandton", -26.103, 28.062);
    private static final Intersection NO_COORDS =
            new Intersection("INT-035", "Zone 6 & Main Rd", "Diepkloof", null, null);

    @Test
    @DisplayName("Distance between two nearby intersections is under a kilometre")
    void measuresShortDistance() {
        double km = RouteEstimator.haversineKm(-26.106, 28.058, -26.103, 28.062);

        assertTrue(km > 0.3 && km < 0.8, "expected roughly half a kilometre, got " + km);
    }

    @Test
    @DisplayName("Free-flowing traffic leaves the base time unchanged")
    void levelZeroIsFreeFlow() {
        assertEquals(1.0, RouteEstimator.congestionFactor(0));
    }

    @Test
    @DisplayName("Congestion makes the same trip take longer")
    void congestionIncreasesTravelTime() {
        double free = RouteEstimator.estimate(SANDTON, GRAYSTON, 0).estimatedMinutes();
        double busy = RouteEstimator.estimate(SANDTON, GRAYSTON, 8).estimatedMinutes();

        assertTrue(busy > free, "gridlock should be slower than free flow");
        assertEquals(2.0, RouteEstimator.congestionFactor(8));
    }

    @Test
    @DisplayName("The estimate scales with the congestion level, not arbitrarily")
    void scalesPredictably() {
        RouteEstimator.Estimate free = RouteEstimator.estimate(SANDTON, GRAYSTON, 0);
        RouteEstimator.Estimate gridlock = RouteEstimator.estimate(SANDTON, GRAYSTON, 8);

        assertEquals(free.distanceKm(), gridlock.distanceKm());
        assertEquals(Math.round(free.estimatedMinutes() * 2 * 100),
                Math.round(gridlock.estimatedMinutes() * 100));
    }

    @Test
    @DisplayName("A missing coordinate falls back to a default distance and says so")
    void flagsEstimatedDistance() {
        RouteEstimator.Estimate estimate = RouteEstimator.estimate(SANDTON, NO_COORDS, 2);

        assertTrue(estimate.distanceEstimated());
        assertEquals(RouteEstimator.DEFAULT_DISTANCE_KM, estimate.distanceKm());
    }

    @Test
    @DisplayName("A route with full coordinates is not flagged as estimated")
    void realDistanceIsNotFlagged() {
        assertFalse(RouteEstimator.estimate(SANDTON, GRAYSTON, 0).distanceEstimated());
    }
}
