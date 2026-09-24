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

    private final IntersectionCleaner cleaner = new IntersectionCleaner();

    private IntersectionCleaner.Result clean(String... lines) {
        return cleaner.clean(List.of(lines));
    }

    private static final String HEADER = "intersection_id,description,region,latitude,longitude";

    @Test
    @DisplayName("Names and ids are normalised, whitespace collapsed")
    void normalisesNamesAndIds() {
        IntersectionCleaner.Result result = clean(HEADER,
                " int-001 ,  empire rd &   jan smuts ave ,Parktown,-26.1830,28.0300");

        Intersection first = result.intersections().get(0);
        assertEquals("INT-001", first.id());
        assertEquals("Empire Rd & Jan Smuts Ave", first.name());
        assertEquals("Parktown", first.district());
    }

    @Test
    @DisplayName("Footnote markers and bracketed notes are stripped")
    void stripsAnnotations() {
        assertEquals("Witkoppen Rd & Cedar Rd", IntersectionCleaner.titleCase("Witkoppen Rd & Cedar Rd (north)"));
        assertEquals("Empire Rd", IntersectionCleaner.titleCase("Empire Rd*"));
    }

    @Test
    @DisplayName("Short all-caps tokens such as CBD and N1 are preserved")
    void keepsAcronyms() {
        assertEquals("Johannesburg CBD", IntersectionCleaner.titleCase("johannesburg CBD"));
        assertEquals("N1 Offramp", IntersectionCleaner.titleCase("N1 offramp"));
    }

    @Test
    @DisplayName("Columns are matched by header name, so a different order still works")
    void mapsColumnsByHeaderName() {
        IntersectionCleaner.Result result = clean(
                "region,latitude,longitude,intersection_id,description",
                "Sandton,-26.1060,28.0580,INT-009,Rivonia Rd & Katherine St");

        Intersection only = result.intersections().get(0);
        assertEquals("INT-009", only.id());
        assertEquals("Sandton", only.district());
        assertEquals(-26.1060, only.lat());
    }

    @Test
    @DisplayName("Identical duplicates are merged rather than repeated")
    void mergesDuplicates() {
        IntersectionCleaner.Result result = clean(HEADER,
                "INT-009,Rivonia Rd & Katherine St,Sandton,-26.1060,28.0580",
                "int-009,rivonia rd & katherine st,sandton,-26.1060,28.0580");

        assertEquals(1, result.intersections().size());
        assertEquals(1, result.report().duplicatesMerged());
        assertEquals(0, result.report().rejected().size());
    }

    @Test
    @DisplayName("The same id with different details is rejected, never guessed")
    void rejectsConflicts() {
        IntersectionCleaner.Result result = clean(HEADER,
                "INT-004,William Nicol Dr & Republic Rd,Randburg,-26.1010,28.0000",
                "INT-004,William Nicol Dr & Republic Rd,Sandton,-26.1010,28.0000");

        assertEquals(1, result.intersections().size());
        assertEquals("Randburg", result.intersections().get(0).district());
        assertTrue(result.report().rejected().get(0).reason().startsWith("conflict"));
    }

    @Test
    @DisplayName("Rows missing a required field are rejected with the reason")
    void rejectsMissingFields() {
        IntersectionCleaner.Result result = clean(HEADER,
                ",Kerk St & Von Wielligh St,Johannesburg CBD,-26.2030,28.0460",
                "INT-032,,Melville,-26.1750,28.0090",
                "INT-031,Fox St & Sauer St,N/A,-26.2060,28.0380");

        assertEquals(List.of("missing intersection id", "missing intersection name", "missing district"),
                result.report().rejected().stream().map(CleaningReport.Rejection::reason).toList());
    }

    @Test
    @DisplayName("A row with the wrong number of columns is rejected")
    void rejectsMalformedRows() {
        IntersectionCleaner.Result result = clean(HEADER,
                "INT-036,Corlett Dr & Athol Oaklands,Melrose,-26.1330");

        assertEquals(1, result.report().rejected().size());
        assertEquals("expected 5 columns, found 4", result.report().rejected().get(0).reason());
    }

    @Test
    @DisplayName("A bad coordinate drops the coordinate, not the whole intersection")
    void keepsRecordWhenCoordinatesAreUnusable() {
        IntersectionCleaner.Result result = clean(HEADER,
                "INT-033,Beyers Naude Dr & Rustenburg Rd,Blackheath,not-a-number,28.9100",
                "INT-034,Sloane St & Main Rd,Bryanston,-99.5000,28.0200");

        assertEquals(2, result.intersections().size());
        assertTrue(result.intersections().stream().noneMatch(Intersection::hasCoordinates));
        assertEquals(0, result.report().rejected().size());
    }

    @Test
    @DisplayName("Comma decimal separators are accepted")
    void acceptsCommaDecimals() {
        assertEquals(-26.183, IntersectionCleaner.coordinate("-26,183", 90));
        assertNull(IntersectionCleaner.coordinate("", 90));
    }

    @Test
    @DisplayName("Every row in the shipped file is accepted, merged or rejected with a reason")
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
        assertTrue(report.rejected().size() > 0);
    }
}
