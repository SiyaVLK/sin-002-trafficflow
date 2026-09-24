package co.wethinkcode.trafficflow;

/**
 * A cleaned intersection record, matching the columns of the legacy export.
 *
 * <p>Three of the four fields are nullable on purpose. The brief's own worked
 * example makes the rule explicit: a value the source never gave is kept as
 * null "rather than dropped or guessed, so downstream services can see it's
 * missing". Defaulting would be more convenient and less true.
 *
 * @param id         canonical upper-case id, e.g. INT-1005 — the only required field
 * @param district   canonical district, or null when the export did not say
 * @param signalType one of {@link SignalTypes}, or null when unrecorded
 * @param active     true, false, or null when the flag said neither
 */
public record Intersection(String id, String district, String signalType, Boolean active) {
}
