package co.wethinkcode.trafficflow;

/**
 * Routing's own view of an intersection, as received from intersection-service.
 * Nullable fields mean the legacy data never recorded them; routing says so in
 * its response rather than guessing.
 */
public record Intersection(String id, String district, String signalType, Boolean active) {
}
