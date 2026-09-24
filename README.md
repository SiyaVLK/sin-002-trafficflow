# TrafficFlow

Urban traffic management as five independent Java services: legacy data is
cleaned once, intersections are validated over REST, and the congestion level
is broadcast over an ActiveMQ topic so routing never has to poll for it. A
watchdog listens for heartbeats and alerts when the intersection service goes
quiet.

Built for the WeThinkCode_ **Systems Integration** elective, September 2026.

---

## Services

| Service | Port | Responsibility |
|---|---|---|
| ingestion-service | 7020 | Cleans `intersections-legacy.csv` and serves the result |
| intersection-service | 7021 | Source of truth for intersection and district validation |
| congestion-service | 7022 | City-wide congestion level on the 0–8 scale |
| routing-service | 7023 | Travel-time estimates from intersections and congestion |
| watchdog-service | 7024 | Alerts when intersection-service stops sending heartbeats |

Each is an **independent Maven project** — no parent aggregator — in the single
flat package `co.wethinkcode.trafficflow`. They share a JSON contract over the
wire, never compiled code, which is why each keeps its own copy of the small
record types and of `Broker`.

## How the stages map

| Stage | What was built |
|---|---|
| **1 — Data ingestion** | `IntersectionCleaner`: header-driven CSV parsing, normalisation, de-duplication, and a rejection reason for every row it cannot use. `GET /cleaning-report` shows exactly what it did. Issue categories are listed in [ingestion-service/README.md](ingestion-service/README.md). |
| **2 — REST integration** | intersection-service loads from ingestion-service and validates lookups; routing-service calls it to check both ends of a route, and reads the congestion level. Explicit connect and request timeouts throughout. |
| **3 — Asynchronous messaging** | congestion-service publishes every change to `congestion-events-topic`; routing-service subscribes instead of polling. Messages carry a version so duplicates and out-of-order delivery are ignored. |
| **4 — Watchdog** | intersection-service sends a heartbeat every 5s on `intersection-heartbeat-queue`; the watchdog declares it DOWN after three missed beats and RECOVERED when they return. |

## Running it

Requires JDK 21+ and Maven 3.8+.

```powershell
.\scripts\build-all.ps1          # builds and tests all five services
# start the broker - see common\README.md
.\scripts\run-all.ps1            # one window per service
```

Then:

```powershell
irm http://localhost:7020/cleaning-report
irm http://localhost:7021/intersections/INT-009
irm "http://localhost:7023/route?from=INT-009&to=INT-010"
.\scripts\set-congestion.ps1 6 "Accident on the M1"
irm "http://localhost:7023/route?from=INT-009&to=INT-010"   # slower now
irm http://localhost:7024/status
```

The second route call returns a longer estimate because congestion-service
published the change and routing-service was already subscribed — no polling,
no restart.

## Things worth demonstrating

**A topic for broadcasts, a queue for heartbeats.** The congestion level goes
on a topic because every interested service should see every change. The
heartbeat goes on a queue because *absence* is the signal: a queue holds
messages while the watchdog restarts, so a gap means the sender stopped rather
than that nobody was listening.

**The REST endpoint survives Stage 3.** A subscriber that has just started has
missed every message published before it connected, so routing primes itself
once over REST and lets the topic keep it current from then on. "Subscribed" is
not the same as "informed".

**Stop the broker mid-demo.** Congestion still records the change and still
reports it over REST — the response says `"published": false` rather than
pretending. Routing keeps using the last level it was told instead of failing.
Start the broker again and both reconnect on their own within a few seconds.

**Stop intersection-service.** The watchdog reports DOWN after about 16
seconds, and routing returns 503 rather than inventing a route it cannot
validate. Start it again: RECOVERED.

## Tests

43 tests across the five services, all runnable without a broker or a network:

| Service | Covers |
|---|---|
| ingestion | Every data-quality category, and that no row is unaccounted for |
| intersection | Case-insensitive lookup, district filtering, refresh semantics |
| congestion | The 0–8 range, version increments, no-op updates |
| routing | Distance and congestion maths; duplicate and out-of-order messages |
| watchdog | UNKNOWN at start-up, tolerance of one missed beat, one alert per outage |

## Deliberate limitations

- State is in memory. Restarting congestion-service resets the level to 0.
- One instance of each service. Scaling routing out would need the congestion
  level in a shared store, or a durable subscription per instance.
- The travel-time model is straight-line distance and a congestion multiplier,
  not a traffic simulation. It is deterministic and testable, which is what the
  integration work needs from it.
- The watchdog is not itself watched.

## Tech

Java 21 · Maven (five independent projects) · Javalin · Jackson · ActiveMQ
Classic 6 (Jakarta JMS) · JUnit 5

## Development notes

The initial scaffold was generated with AI assistance. All code was reviewed,
run, tested and debugged by me, and I can walk through any part of it —
including why the congestion level is a topic and the heartbeat is a queue.
