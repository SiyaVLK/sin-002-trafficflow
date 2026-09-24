package co.wethinkcode.trafficflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the legacy intersections export into clean, canonical records.
 *
 * <p>The parser is <b>header driven</b>: it reads the first non-comment row and
 * maps columns by name, accepting the common aliases for each field. That means
 * a legacy file with {@code intersection_id, description, region, latitude,
 * longitude} is handled without code changes, and a column appearing in a
 * different order does not silently shift every value one place left.
 *
 * <p>Cleaning rules, applied in order:
 * <ol>
 *   <li>Blank lines and {@code #} comments are skipped and not counted.</li>
 *   <li>A row whose column count does not match the header is rejected.</li>
 *   <li>Placeholder values ({@code N/A}, {@code NULL}, {@code -}, {@code ?},
 *       {@code unknown}) are treated as empty.</li>
 *   <li>Whitespace is trimmed and collapsed; ids are upper-cased; names and
 *       districts are title-cased; footnote markers ({@code *}) and bracketed
 *       notes are stripped.</li>
 *   <li>Id, name and district are required. A row missing any of them is
 *       rejected.</li>
 *   <li>Coordinates are parsed leniently (comma decimal separators are
 *       accepted) and range-checked. An out-of-range or unparseable coordinate
 *       drops the coordinate, not the whole intersection.</li>
 *   <li>An id repeated with identical details is merged. An id repeated with
 *       <i>different</i> details is rejected, because guessing which row is
 *       correct would silently corrupt the source of truth.</li>
 * </ol>
 */
public class IntersectionCleaner {

    private static final Set<String> PLACEHOLDERS = Set.of("n/a", "na", "null", "-", "--", "?", "unknown", "tbd");

    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "id", List.of("id", "intersection_id", "intersectionid", "code", "ref"),
            "name", List.of("name", "intersection", "intersection_name", "description", "label"),
            "district", List.of("district", "region", "area", "suburb", "zone"),
            "lat", List.of("lat", "latitude", "y"),
            "lon", List.of("lon", "lng", "long", "longitude", "x"));

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
            String name = titleCase(cell(cells, columns, "name"));
            String district = titleCase(cell(cells, columns, "district"));

            if (id.isEmpty()) {
                rejected.add(new CleaningReport.Rejection(lineNumber, raw, "missing intersection id"));
                continue;
            }
            if (name.isEmpty()) {
                rejected.add(new CleaningReport.Rejection(lineNumber, raw, "missing intersection name"));
                continue;
            }
            if (district.isEmpty()) {
                rejected.add(new CleaningReport.Rejection(lineNumber, raw, "missing district"));
                continue;
            }

            Double lat = coordinate(cell(cells, columns, "lat"), 90);
            Double lon = coordinate(cell(cells, columns, "lon"), 180);
            Intersection candidate = new Intersection(id, name, district, lat, lon);

            Intersection existing = byId.get(id);
            if (existing != null) {
                if (sameDetails(existing, candidate)) {
                    duplicates++;
                } else {
                    rejected.add(new CleaningReport.Rejection(lineNumber, raw,
                            "conflict: id " + id + " already recorded as '" + existing.name()
                                    + "' in " + existing.district()));
                }
                continue;
            }
            byId.put(id, candidate);
        }

        List<Intersection> cleaned = new ArrayList<>(byId.values());
        cleaned.sort(Comparator.comparing(Intersection::district).thenComparing(Intersection::id));
        return new Result(List.copyOf(cleaned),
                new CleaningReport(rowsRead, cleaned.size(), duplicates, List.copyOf(rejected)));
    }

    // ------------------------------------------------------------- helpers

    /**
     * Field name to column index, for every field we recognise in the header.
     * Unrecognised columns are ignored, which is why a legacy file with extra
     * columns still works.
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
        String value = cells[index].strip();
        if (PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT))) {
            return "";
        }
        return value;
    }

    static String normaliseId(String raw) {
        return raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static String titleCase(String raw) {
        String cleaned = raw
                .replaceAll("\\(.*?\\)", " ")  // bracketed notes
                .replace("*", " ")             // footnote markers
                .replaceAll("\\s+", " ")
                .strip();
        if (cleaned.isEmpty()) {
            return "";
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
        // Keep short all-caps tokens (CBD, N1, M2) as they are.
        if (word.length() <= 3 && word.equals(word.toUpperCase(Locale.ROOT))) {
            return word;
        }
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

    /** @return the coordinate, or null when it is absent, unparseable or out of range */
    static Double coordinate(String raw, double limit) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.replace(",", ".").replace("°", "").strip());
            return Math.abs(value) <= limit ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean sameDetails(Intersection a, Intersection b) {
        return a.name().equalsIgnoreCase(b.name())
                && a.district().equalsIgnoreCase(b.district())
                && Objects.equals(a.lat(), b.lat())
                && Objects.equals(a.lon(), b.lon());
    }
}
