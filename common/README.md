# Broker configuration

Every service that uses messaging reads `BROKER_URL`, defaulting to
`tcp://localhost:61616`. Nothing else needs configuring.

| Destination | Type | Publisher | Subscriber | Why that type |
|---|---|---|---|---|
| `congestion-events-topic` | Topic | congestion-service | routing-service | A broadcast. Every interested service should see every change, and a queue would give each message to only one of them. |
| `intersection-heartbeat-queue` | Queue | intersection-service | watchdog-service | Absence of a message is the signal. A queue holds heartbeats while the watchdog restarts, so a gap really means the sender stopped rather than "I wasn't listening". |

## Starting the broker

**With Docker** (as the brief describes):

```bash
cd common
docker compose up -d
```

**Without Docker.** Docker Desktop cannot be installed on the machine this was
built on — the IT policy has Virtual Machine Platform disabled — so the broker
was run from the ActiveMQ distribution instead:

1. Download ActiveMQ Classic 6.1.x from <https://activemq.apache.org/components/classic/download/>
2. Unzip it anywhere
3. `bin\activemq.bat start` on Windows, `bin/activemq start` elsewhere

Same broker, same port, same behaviour. The services cannot tell the
difference, which is the point of talking to it over a network protocol rather
than embedding it.

The web console at <http://localhost:8161> (admin/admin) is worth having open
during a demo: you can watch messages land on the topic and queue, and see the
queue hold heartbeats while the watchdog is stopped.

## Checking it is up

```powershell
Test-NetConnection localhost -Port 61616      # Windows
```
```bash
nc -z localhost 61616 && echo "broker is up"  # macOS/Linux
```

Services do not require the broker to start. They log a warning, retry every
few seconds, and connect as soon as it appears.
