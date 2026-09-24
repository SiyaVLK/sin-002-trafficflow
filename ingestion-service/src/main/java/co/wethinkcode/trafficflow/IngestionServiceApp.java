package co.wethinkcode.trafficflow;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Stage 1: reads the legacy intersections export, cleans it once, and serves
 * the result over REST for every other service to consume.
 *
 * <p>Cleaning happens here and nowhere else. If each service normalised ids its
 * own way, {@code int-1005} in one and {@code INT-1005} in another would be two
 * different intersections, and validation would quietly start failing.
 *
 * <pre>
 *   GET /health              "OK"
 *   GET /intersections       every cleaned intersection
 *                            ?district=Downtown  ?signal=roundabout  ?active=true
 *   GET /intersections/{id}  one intersection, 404 when unknown
 *   GET /districts           district to intersection count
 *   GET /cleaning-report     what was accepted, merged and rejected, with reasons
 * </pre>
 */
public final class IngestionServiceApp {

    private static final Logger log = LoggerFactory.getLogger(IngestionServiceApp.class);
    private static final int PORT = 7020;
    private static final String DATA_FILE = "intersections-legacy.csv";

    private IngestionServiceApp() {
    }

    public static void main(String[] args) throws IOException {
        IntersectionCleaner.Result cleaned = new IntersectionCleaner().clean(readData(args));
        List<Intersection> intersections = cleaned.intersections();
        CleaningReport report = cleaned.report();

        log.info("Cleaned {}: {} rows read, {} accepted, {} duplicates merged, {} rejected",
                DATA_FILE, report.rowsRead(), report.accepted(),
                report.duplicatesMerged(), report.rejected().size());
        report.rejected().forEach(r ->
                log.warn("  line {} rejected ({}): {}", r.line(), r.reason(), r.raw()));
        log.info("  {} with no district, {} with no signal type, {} with an unknown active flag",
                count(intersections, i -> i.district() == null),
                count(intersections, i -> i.signalType() == null),
                count(intersections, i -> i.active() == null));

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/intersections", ctx -> {
            String district = ctx.queryParam("district");
            String signal = ctx.queryParam("signal");
            String active = ctx.queryParam("active");
            ctx.json(intersections.stream()
                    .filter(i -> district == null || district.isBlank()
                            || (i.district() != null && i.district().equalsIgnoreCase(district.strip())))
                    .filter(i -> signal == null || signal.isBlank()
                            || (i.signalType() != null && i.signalType().equalsIgnoreCase(signal.strip())))
                    .filter(i -> active == null || active.isBlank()
                            || (i.active() != null && i.active() == Boolean.parseBoolean(active.strip())))
                    .toList());
        });

        app.get("/intersections/{id}", ctx -> {
            String id = IntersectionCleaner.normaliseId(ctx.pathParam("id"));
            intersections.stream()
                    .filter(i -> i.id().equals(id))
                    .findFirst()
                    .ifPresentOrElse(
                            ctx::json,
                            () -> ctx.status(404).json(Map.of("error", "Unknown intersection: " + id)));
        });

        // Intersections with no recorded district are counted separately rather
        // than bucketed under a made-up name.
        app.get("/districts", ctx -> {
            Map<String, Long> counts = intersections.stream()
                    .filter(i -> i.district() != null)
                    .collect(Collectors.groupingBy(Intersection::district, TreeMap::new, Collectors.counting()));
            ctx.json(Map.of(
                    "districts", counts,
                    "withoutDistrict", count(intersections, i -> i.district() == null)));
        });

        app.get("/cleaning-report", ctx -> ctx.json(report));

        log.info("ingestion-service listening on http://localhost:{} with {} intersections",
                PORT, intersections.size());
    }

    private static long count(List<Intersection> intersections, java.util.function.Predicate<Intersection> test) {
        return intersections.stream().filter(test).count();
    }

    /**
     * Reads the data file from the first argument if given, otherwise from the
     * classpath — handy for pointing the service at another export without
     * rebuilding.
     */
    private static List<String> readData(String[] args) throws IOException {
        if (args.length > 0) {
            Path path = Path.of(args[0]);
            log.info("Reading intersections from {}", path.toAbsolutePath());
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        }
        try (InputStream in = IngestionServiceApp.class.getResourceAsStream("/" + DATA_FILE)) {
            if (in == null) {
                throw new IOException(DATA_FILE + " not found on the classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        }
    }
}
