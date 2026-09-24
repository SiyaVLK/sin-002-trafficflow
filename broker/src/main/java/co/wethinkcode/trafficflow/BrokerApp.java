package co.wethinkcode.trafficflow;

import org.apache.activemq.broker.BrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the ActiveMQ broker as an ordinary Java process.
 *
 * <p>The brief suggests {@code docker compose up} in {@code common/}, and that
 * compose file is still there. This module exists because Docker Desktop cannot
 * be installed on the machine this was built on — the IT policy has Virtual
 * Machine Platform disabled — so the broker runs from a jar instead.
 *
 * <p>It is still a <b>separate process on its own port</b>, not a broker
 * embedded inside a service. Every other service reaches it over TCP exactly as
 * it would a production broker, and it can be killed independently to
 * demonstrate what happens when the messaging infrastructure goes away. Only
 * the packaging differs.
 *
 * <p>{@code persistent=false} keeps messages in memory: they survive a
 * <i>consumer</i> restarting, because the broker holds them, but not the broker
 * itself restarting. That is the accepted trade-off for a local system, and it
 * is why the congestion service reports {@code "published": false} rather than
 * pretending when the broker is away.
 */
public final class BrokerApp {

    private static final Logger log = LoggerFactory.getLogger(BrokerApp.class);

    private BrokerApp() {
    }

    public static void main(String[] args) throws Exception {
        String bindUrl = args.length > 0 ? args[0] : "tcp://0.0.0.0:61616";

        BrokerService broker = new BrokerService();
        broker.setBrokerName("trafficflow");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.addConnector(bindUrl);
        broker.start();
        broker.waitUntilStarted();

        log.info("TrafficFlow broker listening on {}", bindUrl);
        log.info("  topic congestion-events-topic     congestion-service -> routing-service");
        log.info("  queue intersection-heartbeat-queue intersection-service -> watchdog");
        log.info("Stop it with Ctrl+C to see how the services cope.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                broker.stop();
                broker.waitUntilStopped();
                log.info("Broker stopped cleanly");
            } catch (Exception e) {
                log.error("Broker did not stop cleanly", e);
            }
        }, "broker-shutdown"));

        Thread.currentThread().join();
    }
}
