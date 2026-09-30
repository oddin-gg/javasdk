package com.oddin.oddsfeedsdk.internal.amqp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The transport against a real broker: delivery, bounds, channel replacement and reconnection. */
class AmqpTransportTest {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static @Nullable FakeFeed feed;

    private final Recorded events = new Recorded();
    private final List<AmqpTransport> open = new ArrayList<>();

    @BeforeAll
    static void startTheBroker() {
        feed = FakeFeed.start();
    }

    @AfterAll
    static void stopTheBroker() {
        requireNonNull(feed).close();
    }

    @AfterEach
    void close() {
        open.forEach(AmqpTransport::close);
    }

    @Test
    void aSessionGetsWhatItsKeysSelectAndTheSdksConsumerTheAlives() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        assertThat(events.events).containsExactly("connecting", "up");

        assertThat(feed().publishFixture(ODDS_CHANGE)).isTrue();
        RawDelivery change = next(session);
        assertThat(change.routingKey()).contains(".odds_change.");
        assertThat(change.body()).isNotNull();
        assertThat(change.sentAt()).isNotNull();
        assertThat(change.epoch()).isEqualTo(session.epoch());
        session.ack(change);

        assertThat(feed().publishFixture("feed/alive/alive.xml")).isTrue();
        assertThat(next(session).routingKey())
                .as("a session binds the alives too")
                .contains(".alive.");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (events.alives.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(events.alives).as("the SDK's own alive consumer").hasSize(1);

        assertThat(feed().connectionProperties())
                .anySatisfy(line -> assertThat(line)
                        .contains("of-sdk-test")
                        .contains("SDK_version")
                        .contains(SdkVersion.version())
                        .contains("java"));
    }

    @Test
    void theBrokerHandsASessionNoMoreThanThePrefetch() throws Exception {
        AmqpTransport transport = transport(settings(3, 1 << 20), false);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        for (int i = 0; i < 8; i++) {
            feed().publishFixture(ODDS_CHANGE);
        }
        awaitSize(session, 3);
        Thread.sleep(500);
        assertThat(session.queue().size())
                .as("no more than the prefetch unacknowledged")
                .isEqualTo(3);

        session.ack(next(session));
        awaitSize(session, 3);
        assertThat(session.queue().overflowed()).isZero();
    }

    @Test
    void aBodyOverTheMaximumSizeArrivesWithoutItsBody() throws Exception {
        AmqpTransport transport = transport(settings(10, 100), false);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        feed().publishFixture(ODDS_CHANGE);
        RawDelivery delivery = next(session);
        assertThat(delivery.oversized()).isTrue();
        assertThat(delivery.size()).isGreaterThan(100);
        session.ack(delivery);
    }

    @Test
    void aReplacedChannelTakesItsDeliveriesWithItAndTheirAcknowledgementsAreSkipped() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        feed().publishFixture(ODDS_CHANGE);
        feed().publishFixture(ODDS_CHANGE);
        awaitSize(session, 2);
        RawDelivery old = next(session);
        long before = session.epoch();

        session.reset();
        assertThat(session.epoch()).isEqualTo(before + 1);
        assertThat(session.queue().size())
                .as("the old channel's deliveries are out")
                .isZero();
        session.ack(old);
        assertThat(((SessionChannel) session).skippedAcks()).isEqualTo(1);

        feed().publishFixture(ODDS_CHANGE);
        assertThat(next(session).epoch()).isEqualTo(before + 1);
    }

    @Test
    void aLostConnectionIsMadeAgainAndTheSessionsReadOnInANewEpoch() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();

        feed().closeConnections();
        events.await("down"::equals, WAIT);
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
        assertThat(events.events).containsSubsequence("connecting", "up", "down", "recovering", "up");
        assertThat(session.epoch()).isGreaterThan(before);

        feed().publishFixture(ODDS_CHANGE);
        assertThat(next(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void aChannelTheBrokerTakesIsOpenedAgainOnTheLiveConnection() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();

        feed().deleteClientQueues();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (session.epoch() == before && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.epoch()).as("the session's channel, opened again").isGreaterThan(before);
        feed().publishFixture(ODDS_CHANGE);
        assertThat(next(session).epoch()).isEqualTo(session.epoch());

        // and the SDK's own alive consumer
        int alivesBefore = events.alives.size();
        deadline = System.nanoTime() + WAIT.toNanos();
        while (events.alives.size() == alivesBefore && System.nanoTime() < deadline) {
            feed().publishFixture("feed/alive/alive.xml");
            Thread.sleep(200);
        }
        assertThat(events.alives).as("alives again").hasSizeGreaterThan(alivesBefore);
        assertThat(events.events).as("the connection stayed up").containsExactly("connecting", "up");
    }

    @Test
    void aBrokerWhoseCertificateIsNotTrustedIsRefused() throws Exception {
        var onlyTheJdksTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        onlyTheJdksTrust.init((KeyStore) null);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, onlyTheJdksTrust.getTrustManagers(), null);
        AmqpSettings base = settings(10, 1 << 20);
        assertRefusedByTls(withTls(base, base.host(), tls), "PKIX");
    }

    @Test
    void aHostTheCertificateDoesNotNameIsRefused() throws Exception {
        // the machine's own name reaches the broker, and its certificate names only localhost
        String host = InetAddress.getLocalHost().getHostName();
        AmqpSettings base = settings(10, 1 << 20);
        assertRefusedByTls(withTls(base, host, TestTls.clientContext()), "subject alternative");
    }

    @Test
    void aClosedTransportReportsNoDown() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        transport.addSession(allKeys());
        transport.open();
        transport.close();
        Thread.sleep(300);
        assertThat(events.events).containsExactly("connecting", "up");
    }

    @Test
    void anOpenThatFailsLeavesNothingOpen() throws Exception {
        AmqpTransport transport = new AmqpTransport(settings(10, 1 << 20), "no-such-exchange", events, null);
        open.add(transport);
        transport.addSession(allKeys());
        assertThatThrownBy(transport::open)
                .isInstanceOf(InitException.class)
                .hasMessageStartingWith("Failed to open the feed: the broker");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!feed().openConnections().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(feed().openConnections()).isEmpty();
    }

    static AmqpSettings settings(FakeFeed broker, int prefetch, int maxMessageSize) {
        return new AmqpSettings(
                broker.host(),
                broker.port(),
                broker.virtualHost(),
                "test-token",
                TestTls.clientContext(),
                "of-sdk-test",
                prefetch,
                maxMessageSize,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }

    private void assertRefusedByTls(AmqpSettings settings, String why) throws InterruptedException {
        int loginsBefore = feed().logins().size();
        var transport = new AmqpTransport(settings, FakeFeed.EXCHANGE, events, null);
        open.add(transport);
        transport.addSession(allKeys());
        assertThatThrownBy(transport::open)
                .isInstanceOf(InitException.class)
                .satisfies(e -> assertThat(chain(e))
                        .as("the cause chain")
                        .anySatisfy(cause -> assertThat(cause)
                                .isInstanceOfAny(SSLHandshakeException.class, CertificateException.class))
                        .anySatisfy(cause ->
                                assertThat(String.valueOf(cause.getMessage())).contains(why)));
        assertThat(feed().logins()).as("no login got as far as the broker").hasSize(loginsBefore);
    }

    private static List<Throwable> chain(Throwable failure) {
        var chain = new ArrayList<Throwable>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private static AmqpSettings withTls(AmqpSettings base, String host, SSLContext tls) {
        return new AmqpSettings(
                host,
                base.port(),
                base.virtualHost(),
                base.accessToken(),
                tls,
                base.connectionName(),
                base.prefetch(),
                base.maxMessageSize(),
                base.heartbeat(),
                base.connectTimeout(),
                base.firstBackoff(),
                base.maxBackoff(),
                base.resourceBackoff());
    }

    private AmqpSettings settings(int prefetch, int maxMessageSize) {
        return settings(feed(), prefetch, maxMessageSize);
    }

    private AmqpTransport transport(AmqpSettings settings, boolean withAlives) {
        var transport = new AmqpTransport(settings, FakeFeed.EXCHANGE, events, withAlives ? events::alive : null);
        open.add(transport);
        return transport;
    }

    private static List<String> allKeys() {
        return RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true);
    }

    private static RawDelivery next(SessionTransport session) throws InterruptedException {
        RawDelivery delivery = session.queue().poll(WAIT);
        assertThat(delivery).as("a delivery within " + WAIT).isNotNull();
        return requireNonNull(delivery);
    }

    private static void awaitSize(SessionTransport session, int size) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (session.queue().size() < size && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.queue().size()).isEqualTo(size);
    }

    private static FakeFeed feed() {
        return requireNonNull(feed);
    }
}
