package co.wethinkcode.trafficflow;

/** Routing's own view of an intersection, as received from intersection-service. */
public record Intersection(String id, String name, String district, Double lat, Double lon) {

    public boolean hasCoordinates() {
        return lat != null && lon != null;
    }
}
