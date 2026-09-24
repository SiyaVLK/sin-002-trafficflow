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

/**
 * Stage 1: reads the legacy intersections export, cleans it once, and serves
 * the result over REST for every other service to consume.
 *
 * <p>Cleaning happens here and nowhere else. If each service cleaned names its
 * own way, "OXFORD RD" in one service and "Oxford Rd" in another would be two
 * different intersections, and validation would silently fail.
 *
 * <pre>
 *   GET /health           "OK"
 *   GET /intersections    every cleaned intersection
 *   GET /intersections/{id}
 *   GET /districts        district to intersection count
 *   GET /cleaning-report  what was accepted, merged and rejected, with reasons
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

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/intersections", ctx -> {
            String district = ctx.queryParam("district");
            ctx.json(district == null || district.isBlank()
                    ? intersections
                    : intersections.stream()
                            .filter(i -> i.district().equalsIgnoreCase(district.strip()))
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

        app.get("/districts", ctx -> ctx.json(intersections.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Intersection::district, java.util.TreeMap::new, java.util.stream.Collectors.counting()))));

        app.get("/cleaning-report", ctx -> ctx.json(report));

        log.info("ingestion-service listening on http://localhost:{} with {} intersections",
                PORT, intersections.size());
    }

    /**
     * Reads the data file from the first argument if given, otherwise from the
     * classpath. The argument makes it easy to point the service at the brief's
     * own CSV without rebuilding.
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
