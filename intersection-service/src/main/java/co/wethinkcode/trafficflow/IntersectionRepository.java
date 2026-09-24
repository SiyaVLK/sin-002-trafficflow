package co.wethinkcode.trafficflow;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The in-memory source of truth for intersection validation, loaded from
 * ingestion-service and refreshable without a restart.
 *
 * <p>Lookups are case-insensitive on the id so that a caller passing
 * {@code int-009} or {@code INT-009} gets the same answer. Callers should not
 * have to know how ids happen to be cased.
 */
public class IntersectionRepository {

    private final Map<String, Intersection> byId = new ConcurrentHashMap<>();
    private volatile List<Intersection> ordered = List.of();

    public void replaceAll(List<Intersection> intersections) {
        byId.clear();
        intersections.forEach(i -> byId.put(key(i.id()), i));
        ordered = List.copyOf(intersections);
    }

    public List<Intersection> all() {
        return ordered;
    }

    public Optional<Intersection> find(String id) {
        return Optional.ofNullable(byId.get(key(id)));
    }

    public List<Intersection> inDistrict(String district) {
        String wanted = district == null ? "" : district.strip();
        return ordered.stream()
                .filter(i -> i.district().equalsIgnoreCase(wanted))
                .toList();
    }

    public Map<String, Long> districtCounts() {
        return ordered.stream().collect(Collectors.groupingBy(
                Intersection::district, TreeMap::new, Collectors.counting()));
    }

    public int size() {
        return ordered.size();
    }

    private static String key(String id) {
        return id == null ? "" : id.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
