package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntersectionRepositoryTest {

    private final IntersectionRepository repository = new IntersectionRepository();

    @BeforeEach
    void load() {
        repository.replaceAll(List.of(
                new Intersection("INT-009", "Rivonia Rd & Katherine St", "Sandton", -26.106, 28.058),
                new Intersection("INT-010", "Grayston Dr & Sandton Dr", "Sandton", -26.103, 28.062),
                new Intersection("INT-014", "Commissioner St & Eloff St", "Johannesburg CBD", -26.205, 28.043)));
    }

    @Test
    @DisplayName("Lookup ignores case and stray whitespace in the id")
    void lookupIsForgiving() {
        assertTrue(repository.find("int-009").isPresent());
        assertTrue(repository.find(" INT-009 ").isPresent());
        assertEquals("Sandton", repository.find("INT-009").orElseThrow().district());
    }

    @Test
    @DisplayName("An unknown id returns empty rather than throwing")
    void unknownIdIsEmpty() {
        assertTrue(repository.find("INT-999").isEmpty());
    }

    @Test
    @DisplayName("Intersections can be filtered by district, case-insensitively")
    void filtersByDistrict() {
        assertEquals(2, repository.inDistrict("sandton").size());
        assertEquals(0, repository.inDistrict("Soweto").size());
    }

    @Test
    @DisplayName("District counts summarise the loaded data")
    void countsDistricts() {
        assertEquals(Map.of("Sandton", 2L, "Johannesburg CBD", 1L), repository.districtCounts());
    }

    @Test
    @DisplayName("A refresh replaces the previous data rather than adding to it")
    void refreshReplaces() {
        repository.replaceAll(List.of(
                new Intersection("INT-001", "Empire Rd & Jan Smuts Ave", "Parktown", -26.183, 28.030)));

        assertEquals(1, repository.size());
        assertTrue(repository.find("INT-009").isEmpty());
    }
}
