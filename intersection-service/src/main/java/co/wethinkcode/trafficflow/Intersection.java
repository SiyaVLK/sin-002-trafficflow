package co.wethinkcode.trafficflow;

/**
 * An intersection as this service receives it from ingestion-service.
 *
 * <p>Deliberately its own copy rather than a shared class: services share a
 * JSON contract over the wire, not compiled code. That is what lets
 * ingestion-service add a field without breaking this service's build.
 *
 * <p>district, signalType and active are nullable, and stay that way. A value
 * the legacy export never recorded is not something this service should invent
 * on its behalf.
 */
public record Intersection(String id, String district, String signalType, Boolean active) {
}
