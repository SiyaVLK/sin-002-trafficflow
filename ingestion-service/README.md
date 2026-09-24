# ingestion-service (port 7020)

Stage 1. Reads `intersections-legacy.csv`, cleans it once, and serves the
result over REST. Every other service treats this output as the source of truth
for intersection ids, districts and signal types.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /health` | `OK` |
| `GET /intersections` | Every cleaned intersection. Filters: `?district=Downtown`, `?signal=roundabout`, `?active=true` |
| `GET /intersections/{id}` | One intersection, 404 when unknown. Id matching is case-insensitive |
| `GET /districts` | District to intersection count, plus how many have no district recorded |
| `GET /cleaning-report` | Rows read, accepted, merged and rejected — each rejection with a line number and a reason |

## Data quality issues handled

Each issue named in the brief, and what the cleaner does with it:

| Issue | Example in the export | Handling |
|---|---|---|
| Inconsistent casing in ids | `int-1002`, `INT-1003 ` | Upper-cased and stripped, so `int-1005` and `INT-1005` are one intersection |
| Inconsistent casing in values | `downtown`, `DOWNTOWN`, `ROUNDABOUT` | Districts title-cased, signal types mapped to a canonical value |
| Padding | `" Downtown "`, `"Down  town"`, header `"District "` | Trimmed, internal runs of spaces collapsed — the header too, which is why `District ` still maps |
| Duplicate records | `INT-1005` and `int-1005` | Merged into one record when the cleaned details agree; counted in `duplicatesMerged` |
| Conflicting duplicates | same id, different district | **Rejected** with a reason. Guessing which row is right would corrupt the source of truth every other service depends on |
| Missing / placeholder values | blank, `N/A`, `n/a`, `TBD`, `unknown`, `-`, `NaN` | All mean "no value" and become `null` — never a district literally named "N/A" |
| Inconsistent boolean flags | `Y`, `yes`, `1`, `true`, `TRUE`, `N`, `no`, `0`, `FALSE` | Parsed to a real boolean |
| Unrecognised flags | `unknown`, blank | `null`, meaning not known — see below |
| Naming and spelling variants | `4-way`, `4-Way`, `four-way`, `traffic-circle` | Collapsed to one canonical value: `4-way`, `pedestrian`, `roundabout`, `stop-sign` |
| Malformed rows | a row with 3 cells instead of 4 | Rejected, reporting both counts |

The export in this repo has no date or numeric columns. If a future export adds
them, they belong in the same place: one normaliser per column type, applied
here rather than in each consuming service.

### Two decisions worth defending

**Missing stays missing.** A blank signal type becomes `null`, not a default,
and a row with no district is kept with `district: null` rather than dropped.
The brief's worked example makes the same call: kept "rather than dropped or
guessed, so downstream services can see it's missing". Routing then warns on
the uncertainty instead of inheriting a fabricated value.

**Only the id is required.** A record with no id cannot be referenced by any
other service, so there is nothing useful to keep. Everything else can be
missing and still leave a usable record.

**Nothing disappears unexplained.** `rowsRead == accepted + duplicatesMerged +
rejected` is asserted by a test, and every rejection carries a line number and a
reason so the data owner can fix the source.

## Running

```bash
mvn package
java -jar target/ingestion-service.jar

# or point it at another export without rebuilding:
java -jar target/ingestion-service.jar path/to/intersections-legacy.csv
```

Columns are matched by **header name** — `intersection_id`/`id`,
`District`/`region`, `signal_type`/`type`, `active_flag`/`active` — so a file
with the columns in a different order, or with extra columns, works unchanged.

## Tests

```bash
mvn test
```

15 tests, including the brief's own worked example as a test case: the five raw
rows in this README's spec produce exactly the four cleaned records it
documents.
