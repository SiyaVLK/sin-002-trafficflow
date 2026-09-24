package co.wethinkcode.trafficflow;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Thin wrapper over the ActiveMQ client: publish to a topic or queue, and
 * subscribe to either.
 *
 * <p>Each service keeps its own copy because the brief requires independent
 * Maven projects with no parent aggregator, so there is no shared module to put
 * it in. The trade-off is deliberate: a little duplication in exchange for
 * services that can be built, versioned and deployed separately.
 *
 * <p>Two design points worth knowing:
 * <ul>
 *   <li><b>Plain tcp://, not failover://.</b> The failover transport blocks a
 *       send while the broker is down; this fails fast instead, so a caller can
 *       decide what to do rather than hanging.</li>
 *   <li><b>Reconnection is explicit.</b> If the connection drops, the
 *       subscriber retries every few seconds until the broker returns, so a
 *       broker restart does not need a service restart.</li>
 * </ul>
 */
public class Broker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Broker.class);
    private static final long RETRY_SECONDS = 3;

    private final ActiveMQConnectionFactory factory;
    private final ScheduledExecutorService retries;
    private final Object lock = new Object();

    private Connection publishConnection;
    private volatile boolean connected;
    private volatile boolean closed;

    public Broker(String url) {
        this.factory = new ActiveMQConnectionFactory(url);
        this.retries = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "broker-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static String url() {
        String configured = System.getenv("BROKER_URL");
        return configured == null || configured.isBlank() ? "tcp://localhost:61616" : configured;
    }

    public boolean isConnected() {
        return connected;
    }

    // ------------------------------------------------------------- publish

    public void publishToTopic(String topic, String payload) throws JMSException {
        publish(payload, session -> session.createTopic(topic));
    }

    public void publishToQueue(String queue, String payload) throws JMSException {
        publish(payload, session -> session.createQueue(queue));
    }

    private interface DestinationFactory {
        Destination create(Session session) throws JMSException;
    }

    private void publish(String payload, DestinationFactory destination) throws JMSException {
        synchronized (lock) {
            try {
                Connection connection = publishConnection();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                try {
                    MessageProducer producer = session.createProducer(destination.create(session));
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                    producer.send(session.createTextMessage(payload));
                } finally {
                    session.close();
                }
                connected = true;
            } catch (JMSException e) {
                connected = false;
                closeQuietly(publishConnection);
                publishConnection = null;
                throw e;
            }
        }
    }

    private Connection publishConnection() throws JMSException {
        if (publishConnection == null) {
            Connection connection = factory.createConnection();
            connection.start();
            publishConnection = connection;
        }
        return publishConnection;
    }

    // ----------------------------------------------------------- subscribe

    public void subscribeToTopic(String topic, Consumer<String> handler) {
        subscribe(handler, session -> session.createTopic(topic), "topic " + topic);
    }

    public void subscribeToQueue(String queue, Consumer<String> handler) {
        subscribe(handler, session -> session.createQueue(queue), "queue " + queue);
    }

    private void subscribe(Consumer<String> handler, DestinationFactory destination, String label) {
        AtomicBoolean retryScheduled = new AtomicBoolean(false);
        connect(handler, destination, label, retryScheduled);
    }

    private void connect(Consumer<String> handler, DestinationFactory destination,
                         String label, AtomicBoolean retryScheduled) {
        if (closed) {
            return;
        }
        Connection connection = null;
        try {
            connection = factory.createConnection();
            final Connection active = connection;
            connection.setExceptionListener(e -> {
                log.warn("Lost broker connection for {}: {}", label, e.getMessage());
                connected = false;
                closeQuietly(active);
                scheduleRetry(handler, destination, label, retryScheduled);
            });
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(destination.create(session));
            consumer.setMessageListener(message -> deliver(handler, message, label));
            connection.start();
            connected = true;
            retryScheduled.set(false);
            log.info("Subscribed to {}", label);
        } catch (JMSException e) {
            closeQuietly(connection);
            connected = false;
            log.warn("Cannot subscribe to {} yet ({}), retrying in {}s", label, e.getMessage(), RETRY_SECONDS);
            retryScheduled.set(false);
            scheduleRetry(handler, destination, label, retryScheduled);
        }
    }

    private void deliver(Consumer<String> handler, Message message, String label) {
        try {
            if (message instanceof TextMessage text) {
                handler.accept(text.getText());
            } else {
                log.warn("Ignoring non-text message on {}", label);
            }
        } catch (Exception e) {
            log.error("Handler failed on {}: {}", label, e.getMessage());
        }
    }

    private void scheduleRetry(Consumer<String> handler, DestinationFactory destination,
                               String label, AtomicBoolean retryScheduled) {
        if (closed || !retryScheduled.compareAndSet(false, true)) {
            return;
        }
        retries.schedule(() -> connect(handler, destination, label, retryScheduled),
                RETRY_SECONDS, TimeUnit.SECONDS);
    }

    // --------------------------------------------------------------- close

    @Override
    public void close() {
        closed = true;
        retries.shutdownNow();
        synchronized (lock) {
            closeQuietly(publishConnection);
            publishConnection = null;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (JMSException ignored) {
            // already broken, nothing useful to do
        }
    }
}
