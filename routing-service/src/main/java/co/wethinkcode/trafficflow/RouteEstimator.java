package co.wethinkcode.trafficflow;

/**
 * Turns two intersections and a congestion level into an estimated travel time.
 *
 * <p>The model is deliberately simple and explainable: straight-line distance,
 * an average urban speed, and a penalty that grows with the congestion level.
 * It is not a traffic simulation, and the README says so. What matters for the
 * brief is that routing combines data from two other services into an answer,
 * and that the calculation is pure and therefore testable without any network.
 *
 * <p>When an intersection has no usable coordinates — the legacy data has a few
 * — a default distance is used and the response says the distance was
 * estimated, rather than silently returning a confident wrong number.
 */
public final class RouteEstimator {

    static final double AVERAGE_SPEED_KMH = 35.0;
    static final double DEFAULT_DISTANCE_KM = 4.0;
    static final double EARTH_RADIUS_KM = 6371.0;

    public record Estimate(
            double distanceKm,
            boolean distanceEstimated,
            int congestionLevel,
            double congestionFactor,
            double estimatedMinutes) {
    }

    private RouteEstimator() {
    }

    public static Estimate estimate(Intersection from, Intersection to, int congestionLevel) {
        boolean estimated = !from.hasCoordinates() || !to.hasCoordinates();
        double distanceKm = estimated
                ? DEFAULT_DISTANCE_KM
                : haversineKm(from.lat(), from.lon(), to.lat(), to.lon());

        double factor = congestionFactor(congestionLevel);
        double minutes = distanceKm / AVERAGE_SPEED_KMH * 60 * factor;

        return new Estimate(round(distanceKm), estimated, congestionLevel, factor, round(minutes));
    }

    /**
     * Level 0 leaves the free-flow time alone; level 8 roughly doubles it.
     */
    static double congestionFactor(int congestionLevel) {
        return round(1.0 + 0.125 * Math.max(0, congestionLevel));
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
