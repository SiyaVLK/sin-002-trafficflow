package co.wethinkcode.trafficflow;

import java.util.List;

/**
 * What the cleaner did to the legacy file. Rejected rows are reported with a
 * reason rather than silently dropped, so the owner of the source data can fix
 * it upstream.
 *
 * <p>rowsRead == accepted + duplicatesMerged + rejected.size() always holds,
 * which is asserted by a test. Nothing disappears unaccounted for.
 */
public record CleaningReport(int rowsRead, int accepted, int duplicatesMerged, List<Rejection> rejected) {

    public record Rejection(int line, String raw, String reason) {
    }
}
