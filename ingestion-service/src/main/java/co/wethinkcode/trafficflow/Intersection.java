package co.wethinkcode.trafficflow;

/**
 * A cleaned intersection record.
 *
 * <p>Latitude and longitude are nullable: some legacy rows have no usable
 * coordinates, and dropping an otherwise valid intersection because of a
 * missing coordinate would lose real data. Consumers that need coordinates
 * check for null rather than assuming they are present.
 */
public record Intersection(String id, String name, String district, Double lat, Double lon) {

    public boolean hasCoordinates() {
        return lat != null && lon != null;
    }
}
