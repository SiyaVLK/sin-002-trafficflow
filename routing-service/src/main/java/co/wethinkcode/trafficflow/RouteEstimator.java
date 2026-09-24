package co.wethinkcode.trafficflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Estimates travel time between two intersections.
 *
 * <p>The legacy data has no coordinates, so distance is not available. What it
 * does have is a district and a signal type, and those are enough for a model
 * that is simple, deterministic and honest about what it is:
 *
 * <pre>
 *   minutes = (base + delay(from) + delay(to)) x congestion factor
 * </pre>
 *
 * where the base is shorter within one district than across two, each signal
 * type contributes its own typical delay, and the congestion factor runs from
 * 1.0 at level 0 to 2.0 at level 8.
 *
 * <p>It is not a traffic simulation and the README says so. What the brief
 * needs from it is that routing combines data from two other services into one
 * answer, and that the calculation is pure — so it can be tested without a
 * network, a broker, or a running system.
 *
 * <p>Missing data produces a <b>warning, not a guess</b>. An intersection whose
 * signal is switched off, or whose status was never recorded, still gets an
 * estimate, with the caveat attached. Refusing to answer would be worse: the
 * caller needs a route, and knowing the answer is uncertain is more useful than
 * no answer at all.
 */
public final class RouteEstimator {

    static final double SAME_DISTRICT_BASE_MINUTES = 4.0;
    static final double CROSS_DISTRICT_BASE_MINUTES = 9.0;

    /** Typical delay contributed by passing through each kind of control. */
    private static final Map<String, Double> SIGNAL_DELAY_MINUTES = Map.of(
            "4-way", 0.8,
            "stop-sign", 0.5,
            "pedestrian", 0.4,
            "roundabout", 0.2);

    static final double UNKNOWN_SIGNAL_DELAY_MINUTES = 0.6;

    public record Estimate(
            double baseMinutes,
            double signalDelayMinutes,
            int congestionLevel,
            double congestionFactor,
            double estimatedMinutes,
            List<String> warnings) {
    }

    private RouteEstimator() {
    }

    public static Estimate estimate(Intersection from, Intersection to, int congestionLevel) {
        List<String> warnings = new ArrayList<>();

        double base = sameDistrict(from, to) ? SAME_DISTRICT_BASE_MINUTES : CROSS_DISTRICT_BASE_MINUTES;
        if (from.district() == null || to.district() == null) {
            warnings.add("district not recorded for one or both intersections; "
                    + "assumed a cross-district trip");
        }

        double delay = signalDelay(from, warnings) + signalDelay(to, warnings);
        checkActive(from, warnings);
        checkActive(to, warnings);

        double factor = congestionFactor(congestionLevel);
        double minutes = (base + delay) * factor;

        return new Estimate(base, round(delay), congestionLevel, factor, round(minutes), List.copyOf(warnings));
    }

    /** Level 0 leaves the free-flow time alone; level 8 doubles it. */
    static double congestionFactor(int congestionLevel) {
        return round(1.0 + 0.125 * Math.max(0, congestionLevel));
    }

    static boolean sameDistrict(Intersection from, Intersection to) {
        return from.district() != null
                && to.district() != null
                && from.district().equalsIgnoreCase(to.district());
    }

    static double signalDelay(Intersection intersection, List<String> warnings) {
        String type = intersection.signalType();
        if (type == null) {
            warnings.add("signal type not recorded for " + intersection.id()
                    + "; used the average delay");
            return UNKNOWN_SIGNAL_DELAY_MINUTES;
        }
        Double delay = SIGNAL_DELAY_MINUTES.get(type.toLowerCase());
        if (delay == null) {
            warnings.add("unrecognised signal type '" + type + "' at " + intersection.id()
                    + "; used the average delay");
            return UNKNOWN_SIGNAL_DELAY_MINUTES;
        }
        return delay;
    }

    private static void checkActive(Intersection intersection, List<String> warnings) {
        if (Boolean.FALSE.equals(intersection.active())) {
            warnings.add(intersection.id() + " is marked inactive; the estimate assumes it is passable");
        } else if (intersection.active() == null) {
            warnings.add("active status not recorded for " + intersection.id());
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
