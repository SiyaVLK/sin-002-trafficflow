package co.wethinkcode.trafficflow;

/**
 * An intersection as this service understands it.
 *
 * <p>Deliberately its own copy rather than a shared class: services share a
 * JSON contract over the wire, not compiled code. That is what lets
 * ingestion-service add a field without breaking this service's build.
 */
public record Intersection(String id, String name, String district, Double lat, Double lon) {

    public boolean hasCoordinates() {
        return lat != null && lon != null;
    }
}
