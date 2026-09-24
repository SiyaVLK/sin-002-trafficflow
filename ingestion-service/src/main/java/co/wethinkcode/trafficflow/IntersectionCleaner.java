package co.wethinkcode.trafficflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the legacy intersections export into clean, canonical records.
 *
 * <p>The parser is <b>header driven</b>: it reads the first non-comment row and
 * maps columns by name, trimming the header cells first — the export ships
 * {@code "District "} with a trailing space. Column order and extra columns
 * therefore do not matter.
 *
 * <p>Cleaning rules, in order:
 * <ol>
 *   <li>Blank lines and {@code #} comments are skipped and not counted.</li>
 *   <li>A row whose column count does not match the header is rejected.</li>
 *   <li>Ids lose stray whitespace and are upper-cased, so {@code int-1005} and
 *       {@code INT-1005 } are recognised as the same intersection.</li>
 *   <li>Districts are trimmed, internal double spaces collapsed, and
 *       title-cased: {@code " Downtown "}, {@code downtown} and
 *       {@code DOWNTOWN} all become {@code Downtown}.</li>
 *   <li>Signal types collapse to a canonical set through
 *       {@link SignalTypes}.</li>
 *   <li>The active flag accepts every encoding the export uses
 *       ({@code Y/N}, {@code yes/no}, {@code 1/0}, {@code true/FALSE}).</li>
 *   <li>Placeholders — {@code N/A}, {@code n/a}, {@code TBD}, {@code unknown},
 *       {@code -}, {@code NaN} — all mean "no value" and become null.</li>
 *   <li><b>Missing values are kept as null, not rejected and not defaulted.</b>
 *       Only the id is required: a record with no id cannot be referenced by
 *       any other service, so there is nothing useful to keep.</li>
 *   <li>An id repeated with identical cleaned details is merged. An id repeated
 *       with genuinely different details is rejected rather than guessed, since
 *       every other service treats this output as the source of truth.</li>
 * </ol>
 *
 * <p>Nothing disappears unexplained: {@code rowsRead == accepted +
 * duplicatesMerged + rejected}, asserted by a test, and every rejection carries
 * a line number and a reason.
 */
public class IntersectionCleaner {

    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "id", List.of("intersection_id", "id", "intersectionid", "code", "ref"),
            "district", List.of("district", "region", "area", "zone", "suburb"),
            "signal", List.of("signal_type", "signaltype", "signal", "type", "control", "control_type"),
            "active", List.of("active_flag", "active", "activeflag", "is_active", "status", "enabled"));

    public record Result(List<Intersection> intersections, CleaningReport report) {
    }

    public Result clean(List<String> lines) {
        Map<String, Integer> columns = null;
        int width = 0;
        Map<String, Intersection> byId = new LinkedHashMap<>();
        List<CleaningReport.Rejection> rejected = new ArrayList<>();
        int rowsRead = 0;
        int duplicates = 0;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String raw = lines.get(i);
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] cells = line.split(",", -1);
            if (columns == null) {
                columns = mapColumns(cells);
                width = cells.length;
                continue; // the header is not a data row
            }

            rowsRead++;
            if (cells.length != width) {
                rejected.add(new CleaningReport.Rejection(lineNumber, raw,
                        "expected " + width + " columns, found " + cells.length));
                continue;
            }

            String id = normaliseId(cell(cells, columns, "id"));
            if (id.isEmpty()) {
                rejected.add(new CleaningReport.Rejection(lineNumber, raw, "missing intersection id"));
                continue;
            }

            Intersection candidate = new Intersection(
                    id,
                    district(cell(cells, columns, "district")),
                    SignalTypes.parse(cell(cells, columns, "signal")),
                    ActiveFlag.parse(cell(cells, columns, "active")));

            Intersection existing = byId.get(id);
            if (existing != null) {
                if (sameDetails(existing, candidate)) {
                    duplicates++;
                } else {
                    rejected.add(new CleaningReport.Rejection(lineNumber, raw,
                            "conflict: id " + id + " already recorded as " + describe(existing)));
                }
                continue;
            }
            byId.put(id, candidate);
        }

        List<Intersection> cleaned = new ArrayList<>(byId.values());
        cleaned.sort(Comparator
                .comparing((Intersection i) -> i.district() == null ? "￿" : i.district())
                .thenComparing(Intersection::id));
        return new Result(List.copyOf(cleaned),
                new CleaningReport(rowsRead, cleaned.size(), duplicates, List.copyOf(rejected)));
    }

    // ------------------------------------------------------------- helpers

    /**
     * Field name to column index. Header cells are trimmed and lower-cased
     * before matching, which is what makes {@code "District "} work.
     * Unrecognised columns are ignored rather than shifting anything.
     */
    static Map<String, Integer> mapColumns(String[] header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            String cell = header[i].strip().toLowerCase(Locale.ROOT).replace(" ", "_");
            for (Map.Entry<String, List<String>> alias : COLUMN_ALIASES.entrySet()) {
                if (alias.getValue().contains(cell)) {
                    columns.putIfAbsent(alias.getKey(), i);
                }
            }
        }
        return columns;
    }

    private static String cell(String[] cells, Map<String, Integer> columns, String field) {
        Integer index = columns.get(field);
        if (index == null || index >= cells.length) {
            return "";
        }
        return cells[index];
    }

    static String normaliseId(String raw) {
        return Placeholders.clean(raw).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** @return the title-cased district, or null when the source gave none */
    static String district(String raw) {
        String cleaned = Placeholders.clean(raw);
        if (cleaned.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String word : cleaned.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(capitaliseParts(word));
        }
        return out.toString();
    }

    private static String capitaliseParts(String word) {
        StringBuilder out = new StringBuilder();
        String[] parts = word.split("-", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('-');
            }
            String part = parts[i];
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private static boolean sameDetails(Intersection a, Intersection b) {
        return Objects.equals(a.district(), b.district())
                && Objects.equals(a.signalType(), b.signalType())
                && Objects.equals(a.active(), b.active());
    }

    private static String describe(Intersection intersection) {
        return intersection.signalType() + " in " + intersection.district()
                + " (active=" + intersection.active() + ")";
    }
}
