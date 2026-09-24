package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteEstimatorTest {

    private static final Intersection DOWNTOWN_4WAY =
            new Intersection("INT-1001", "Downtown", "4-way", true);
    private static final Intersection DOWNTOWN_ROUNDABOUT =
            new Intersection("INT-1005", "Downtown", "roundabout", true);
    private static final Intersection MIDTOWN_PEDESTRIAN =
            new Intersection("INT-1002", "Midtown", "pedestrian", true);
    private static final Intersection NO_SIGNAL_TYPE =
            new Intersection("INT-1007", "Eastside", null, true);
    private static final Intersection NO_DISTRICT =
            new Intersection("INT-1015", null, "4-way", true);
    private static final Intersection INACTIVE =
            new Intersection("INT-1009", "Westside", "4-way", false);
    private static final Intersection UNKNOWN_STATUS =
            new Intersection("INT-1013", "Westside", null, null);

    @Test
    @DisplayName("A trip within one district is quicker than one across two")
    void sameDistrictIsQuicker() {
        double within = RouteEstimator.estimate(DOWNTOWN_4WAY, DOWNTOWN_ROUNDABOUT, 0).estimatedMinutes();
        double across = RouteEstimator.estimate(DOWNTOWN_4WAY, MIDTOWN_PEDESTRIAN, 0).estimatedMinutes();

        assertTrue(across > within, "crossing districts should take longer");
    }

    @Test
    @DisplayName("Free-flowing traffic leaves the base time unchanged")
    void levelZeroIsFreeFlow() {
        assertEquals(1.0, RouteEstimator.congestionFactor(0));
    }

    @Test
    @DisplayName("Gridlock doubles the journey")
    void levelEightDoubles() {
        assertEquals(2.0, RouteEstimator.congestionFactor(8));

        double free = RouteEstimator.estimate(DOWNTOWN_4WAY, DOWNTOWN_ROUNDABOUT, 0).estimatedMinutes();
        double gridlock = RouteEstimator.estimate(DOWNTOWN_4WAY, DOWNTOWN_ROUNDABOUT, 8).estimatedMinutes();

        assertEquals(Math.round(free * 2 * 100), Math.round(gridlock * 100));
    }

    @Test
    @DisplayName("Signal type affects the estimate: a roundabout costs less than a four-way")
    void signalTypeMatters() {
        double viaRoundabout = RouteEstimator.estimate(DOWNTOWN_ROUNDABOUT, DOWNTOWN_ROUNDABOUT, 0).estimatedMinutes();
        double viaFourWay = RouteEstimator.estimate(DOWNTOWN_4WAY, DOWNTOWN_4WAY, 0).estimatedMinutes();

        assertTrue(viaFourWay > viaRoundabout);
    }

    @Test
    @DisplayName("A missing signal type warns instead of silently assuming one")
    void warnsOnMissingSignalType() {
        RouteEstimator.Estimate estimate = RouteEstimator.estimate(DOWNTOWN_4WAY, NO_SIGNAL_TYPE, 0);

        assertTrue(estimate.warnings().stream().anyMatch(w -> w.contains("signal type not recorded")));
        assertTrue(estimate.estimatedMinutes() > 0, "an estimate is still returned");
    }

    @Test
    @DisplayName("A missing district warns and is treated as a cross-district trip")
    void warnsOnMissingDistrict() {
        RouteEstimator.Estimate estimate = RouteEstimator.estimate(DOWNTOWN_4WAY, NO_DISTRICT, 0);

        assertEquals(RouteEstimator.CROSS_DISTRICT_BASE_MINUTES, estimate.baseMinutes());
        assertTrue(estimate.warnings().stream().anyMatch(w -> w.contains("district not recorded")));
    }

    @Test
    @DisplayName("An intersection marked inactive is flagged rather than quietly routed through")
    void warnsOnInactiveIntersection() {
        RouteEstimator.Estimate estimate = RouteEstimator.estimate(DOWNTOWN_4WAY, INACTIVE, 0);

        assertTrue(estimate.warnings().stream().anyMatch(w -> w.contains("marked inactive")));
    }

    @Test
    @DisplayName("An unknown active status is reported as unknown, not as working")
    void warnsOnUnknownStatus() {
        RouteEstimator.Estimate estimate = RouteEstimator.estimate(DOWNTOWN_4WAY, UNKNOWN_STATUS, 0);

        assertTrue(estimate.warnings().stream().anyMatch(w -> w.contains("active status not recorded")));
    }

    @Test
    @DisplayName("A clean route carries no warnings at all")
    void cleanRouteHasNoWarnings() {
        assertEquals(0, RouteEstimator.estimate(DOWNTOWN_4WAY, DOWNTOWN_ROUNDABOUT, 3).warnings().size());
    }

    @Test
    @DisplayName("District comparison ignores casing")
    void districtComparisonIgnoresCase() {
        Intersection lowercase = new Intersection("INT-9999", "downtown", "4-way", true);

        assertTrue(RouteEstimator.sameDistrict(DOWNTOWN_4WAY, lowercase));
    }
}
