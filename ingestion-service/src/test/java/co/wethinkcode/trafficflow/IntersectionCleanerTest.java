package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntersectionCleanerTest {

    private static final String HEADER = "intersection_id,District ,signal_type,active_flag";

    private final IntersectionCleaner cleaner = new IntersectionCleaner();

    private IntersectionCleaner.Result clean(String... rows) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(HEADER);
        lines.addAll(List.of(rows));
        return cleaner.clean(lines);
    }

    @Test
    @DisplayName("The brief's worked example produces the shape the brief documents")
    void matchesTheBriefsWorkedExample() {
        IntersectionCleaner.Result result = clean(
                "INT-1001, Downtown ,4-way,Y",
                "INT-1005,Downtown,Roundabout,true",
                "int-1005,downtown ,ROUNDABOUT,TRUE",
                "INT-1007,Eastside,,1",
                "INT-1015,,4-way,Y");

        assertEquals(List.of(
                        new Intersection("INT-1001", "Downtown", "4-way", true),
                        new Intersection("INT-1005", "Downtown", "roundabout", true),
                        new Intersection("INT-1007", "Eastside", null, true),
                        new Intersection("INT-1015", null, "4-way", true)),
                result.intersections());

        assertEquals(1, result.report().duplicatesMerged());
        assertEquals(0, result.report().rejected().size());
    }

    @Test
    @DisplayName("Padding is trimmed, including the double space case")
    void trimsPadding() {
        IntersectionCleaner.Result result = clean("  INT-1001  ,  Down  town  ,4-way,Y");

        assertEquals("INT-1001", result.intersections().get(0).id());
        assertEquals("Down Town", result.intersections().get(0).district());
    }

    @Test
    @DisplayName("Ids and districts are normalised whatever case the export used")
    void normalisesCasing() {
        IntersectionCleaner.Result result = clean(
                "int-1002,MIDTOWN,pedestrian,yes",
                "INT-1003 ,downtown,4-WAY,0");

        assertEquals("INT-1002", result.intersections().get(1).id());
        assertEquals("Midtown", result.intersections().get(1).district());
        assertEquals("Downtown", result.intersections().get(0).district());
    }

    @Test
    @DisplayName("Every boolean encoding in the export is understood")
    void readsEveryBooleanEncoding() {
        assertEquals(Boolean.TRUE, ActiveFlag.parse("Y"));
        assertEquals(Boolean.TRUE, ActiveFlag.parse("yes"));
        assertEquals(Boolean.TRUE, ActiveFlag.parse("1"));
        assertEquals(Boolean.TRUE, ActiveFlag.parse("TRUE"));
        assertEquals(Boolean.FALSE, ActiveFlag.parse("N"));
        assertEquals(Boolean.FALSE, ActiveFlag.parse("no"));
        assertEquals(Boolean.FALSE, ActiveFlag.parse("0"));
        assertEquals(Boolean.FALSE, ActiveFlag.parse("FALSE"));
    }

    @Test
    @DisplayName("An unknown flag stays unknown instead of being guessed either way")
    void unknownFlagStaysNull() {
        assertNull(ActiveFlag.parse("unknown"));
        assertNull(ActiveFlag.parse(""));
        assertNull(ActiveFlag.parse("TBD"));
        assertNull(clean("INT-1013,Westside ,unknown,unknown").intersections().get(0).active());
    }

    @Test
    @DisplayName("Signal type variants collapse to one canonical value")
    void canonicalisesSignalTypes() {
        assertEquals("4-way", SignalTypes.parse("4-Way"));
        assertEquals("4-way", SignalTypes.parse("FOUR-WAY"));
        assertEquals("roundabout", SignalTypes.parse("ROUNDABOUT"));
        assertEquals("stop-sign", SignalTypes.parse("Stop-Sign"));
        assertEquals("pedestrian", SignalTypes.parse(" pedestrian "));
    }

    @Test
    @DisplayName("A missing or unrecognised signal type is null, not a default")
    void missingSignalTypeIsNull() {
        assertNull(SignalTypes.parse(""));
        assertNull(SignalTypes.parse("unknown"));
        assertNull(SignalTypes.parse("something-nobody-has-seen"));
    }

    @Test
    @DisplayName("Placeholder values all mean no value")
    void treatsPlaceholdersAsEmpty() {
        assertEquals("", Placeholders.clean("N/A"));
        assertEquals("", Placeholders.clean(" n/a "));
        assertEquals("", Placeholders.clean("TBD"));
        assertEquals("", Placeholders.clean("-"));
        assertEquals("", Placeholders.clean("NaN"));
        assertEquals("", Placeholders.clean("unknown"));
        assertNull(clean("INT-1020,N/A,4-way,Y").intersections().get(0).district());
    }

    @Test
    @DisplayName("The same intersection under two id casings collapses to one record")
    void mergesDuplicatesAcrossIdCasing() {
        IntersectionCleaner.Result result = clean(
                "INT-1005,Downtown,Roundabout,true",
                "int-1005,downtown ,ROUNDABOUT,TRUE");

        assertEquals(1, result.intersections().size());
        assertEquals(1, result.report().duplicatesMerged());
    }

    @Test
    @DisplayName("The same id with genuinely different details is rejected, never guessed")
    void rejectsConflicts() {
        IntersectionCleaner.Result result = clean(
                "INT-1005,Downtown,Roundabout,true",
                "INT-1005,Midtown,4-way,false");

        assertEquals(1, result.intersections().size());
        assertEquals("Downtown", result.intersections().get(0).district());
        assertTrue(result.report().rejected().get(0).reason().startsWith("conflict"));
    }

    @Test
    @DisplayName("A row with no id is rejected, because nothing can reference it")
    void rejectsMissingId() {
        IntersectionCleaner.Result result = clean(",Downtown,4-way,Y");

        assertEquals(0, result.intersections().size());
        assertEquals("missing intersection id", result.report().rejected().get(0).reason());
    }

    @Test
    @DisplayName("A row with the wrong number of columns is rejected with both counts")
    void rejectsMalformedRows() {
        IntersectionCleaner.Result result = clean("INT-1030,Downtown,4-way");

        assertEquals("expected 4 columns, found 3", result.report().rejected().get(0).reason());
        assertEquals(2, result.report().rejected().get(0).line());
    }

    @Test
    @DisplayName("Blank lines and comments are skipped, not counted as rows")
    void skipsNonData() {
        IntersectionCleaner.Result result = cleaner.clean(List.of(
                "# legacy export", HEADER, "", "   ", "INT-1001,Downtown,4-way,Y"));

        assertEquals(1, result.report().rowsRead());
        assertEquals(1, result.intersections().size());
    }

    @Test
    @DisplayName("Columns are matched by header name, so order and extra columns do not matter")
    void mapsColumnsByHeaderName() {
        IntersectionCleaner.Result result = cleaner.clean(List.of(
                "active_flag,notes,District,intersection_id,signal_type",
                "Y,ignore me,Downtown,INT-1001,4-way"));

        Intersection only = result.intersections().get(0);
        assertEquals("INT-1001", only.id());
        assertEquals("Downtown", only.district());
        assertEquals("4-way", only.signalType());
        assertEquals(Boolean.TRUE, only.active());
    }

    @Test
    @DisplayName("Every row of the shipped file is accepted, merged or rejected with a reason")
    void realFileIsFullyAccountedFor() throws Exception {
        List<String> lines;
        try (InputStream in = getClass().getResourceAsStream("/intersections-legacy.csv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        }

        CleaningReport report = cleaner.clean(lines).report();

        assertEquals(report.rowsRead(),
                report.accepted() + report.duplicatesMerged() + report.rejected().size());
        assertTrue(report.accepted() > 0);
    }
}
