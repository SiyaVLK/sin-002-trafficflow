# ingestion-service (port 7020)

Stage 1. Reads `intersections-legacy.csv`, cleans it once, and serves the
result over REST. Every other service treats this output as the source of truth
for intersection names, ids and districts.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /health` | `OK` |
| `GET /intersections` | Every cleaned intersection (`?district=Sandton` to filter) |
| `GET /intersections/{id}` | One intersection, 404 when unknown |
| `GET /districts` | District to intersection count |
| `GET /cleaning-report` | What was accepted, merged and rejected, with reasons |

## Data quality issues handled

The legacy export has the problems real exports have. Each is handled
deliberately rather than by dropping rows until the file parses.

| Issue | Example in the file | What the cleaner does |
|---|---|---|
| Inconsistent case | `WILLIAM NICOL DR & REPUBLIC RD` | Title-cases names, upper-cases ids |
| Stray whitespace | `  jan smuts ave & bolton rd ` | Trims and collapses internal runs of spaces |
| Footnote markers | `Jan Smuts Ave & Empire Rd*` | Strips `*` |
| Bracketed notes | `Witkoppen Rd & Cedar Rd (north)` | Strips the note, keeps the name |
| Exact duplicates | `INT-002` listed twice identically | Merged, counted in `duplicatesMerged` |
| Conflicting duplicates | `INT-004` in Randburg and in Sandton | **Rejected.** Guessing which row is right would corrupt the source of truth |
| Placeholder values | `N/A`, `NULL`, `-`, `?`, `unknown` | Treated as empty, so they never become a district named "N/A" |
| Missing required fields | Row with no id, name or district | Rejected with the reason |
| Wrong column count | A row with 4 cells instead of 5 | Rejected with expected and actual counts |
| Unparseable coordinates | `not-a-number` | Coordinate dropped, **intersection kept** |
| Out-of-range coordinates | latitude `-99.5` | Same: dropped, not fatal |
| Comma decimal separators | `-26,183` | Parsed as `-26.183` |
| Blank lines and comments | `# Legacy export...` | Skipped, not counted as rows |

Two decisions worth defending:

- **A bad coordinate does not discard the intersection.** Coordinates are used
  for distance estimates; the id, name and district are used for validation.
  Throwing away a valid intersection because one field is broken would lose
  more than it protects. Routing flags the affected estimate instead.
- **Nothing disappears silently.** `rowsRead == accepted + duplicatesMerged +
  rejected` is asserted by a test, and every rejection carries a line number
  and a reason so the data owner can fix the source.

## Using the brief's own CSV

The parser maps columns by **header name**, accepting the usual aliases
(`intersection_id` / `id` / `code`, `description` / `name`, `region` /
`district`, `latitude` / `lat`, `longitude` / `lon` / `lng`). A file with the
columns in a different order, or with extra columns, works unchanged.

To point the service at a file outside the jar:

```bash
java -jar target/ingestion-service.jar path/to/intersections-legacy.csv
```

## Tests

```bash
mvn test
```

`IntersectionCleanerTest` covers each issue category above, including that the
shipped file is fully accounted for.
