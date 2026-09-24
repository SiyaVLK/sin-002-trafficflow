package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntersectionRepositoryTest {

    private final IntersectionRepository repository = new IntersectionRepository();

    @BeforeEach
    void load() {
        repository.replaceAll(List.of(
                new Intersection("INT-1001", "Downtown", "4-way", true),
                new Intersection("INT-1005", "Downtown", "roundabout", true),
                new Intersection("INT-1002", "Midtown", "pedestrian", true),
                new Intersection("INT-1015", null, "4-way", true)));
    }

    @Test
    @DisplayName("Lookup ignores case and stray whitespace in the id")
    void lookupIsForgiving() {
        assertTrue(repository.find("int-1001").isPresent());
        assertTrue(repository.find(" INT-1001 ").isPresent());
        assertEquals("Downtown", repository.find("INT-1001").orElseThrow().district());
    }

    @Test
    @DisplayName("An unknown id returns empty rather than throwing")
    void unknownIdIsEmpty() {
        assertTrue(repository.find("INT-9999").isEmpty());
    }

    @Test
    @DisplayName("Intersections can be filtered by district, case-insensitively")
    void filtersByDistrict() {
        assertEquals(2, repository.inDistrict("downtown").size());
        assertEquals(0, repository.inDistrict("Eastside").size());
    }

    @Test
    @DisplayName("An intersection with no district is not counted under a made-up one")
    void handlesMissingDistrict() {
        assertEquals(Map.of("Downtown", 2L, "Midtown", 1L), repository.districtCounts());
        assertNull(repository.find("INT-1015").orElseThrow().district());
    }

    @Test
    @DisplayName("A refresh replaces the previous data rather than adding to it")
    void refreshReplaces() {
        repository.replaceAll(List.of(new Intersection("INT-2001", "Eastside", "stop-sign", false)));

        assertEquals(1, repository.size());
        assertTrue(repository.find("INT-1001").isEmpty());
    }
}
