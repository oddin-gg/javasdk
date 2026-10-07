package com.oddin.oddsfeedsdk.internal.amqp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.rabbitmq.client.Connection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
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
        assertThat(transport.reconnects())
                .as("the first connect is no reconnect")
                .isZero();

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
    void theSdksAliveConsumerLeavesTheBrokerNothingToAcknowledge() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        transport.addSession(List.of("nothing.for.this.session"));
        transport.open();
        for (int i = 0; i < 3; i++) {
            assertThat(feed().publishFixture("feed/alive/alive.xml")).isTrue();
        }
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (events.alives.size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(events.alives).hasSize(3);
        // acknowledged by the broker as it sent them: none of the prefetch credit a session's are held to
        assertThat(feed().unacknowledged()).isZero();
        var alive = alive(transport);
        assertThatThrownBy(alive::queue).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> alive.ack(events.alives.getFirst())).isInstanceOf(IllegalStateException.class);
        assertThat(alive.isOpen()).isTrue();
    }

    @Test
    void anAliveOverTheMaximumSizeReachesTheSdksConsumerWithoutItsBody() throws Exception {
        String alive = com.oddin.oddsfeed.fakes.Fixtures.read("feed/alive/alive.xml");
        int size = alive.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        AmqpTransport atTheLimit = transport(settings(10, size), true);
        atTheLimit.addSession(List.of("nothing.for.this.session"));
        atTheLimit.open();
        assertThat(feed().publishAsIs(alive)).isTrue();
        RawDelivery whole = nextAlive(0);
        assertThat(whole.body()).as("exactly the maximum").isNotNull().hasSize(size);
        atTheLimit.close();

        AmqpTransport overTheLimit = transport(settings(10, size - 1), true);
        overTheLimit.addSession(List.of("nothing.for.this.session"));
        overTheLimit.open();
        assertThat(feed().publishAsIs(alive)).isTrue();
        RawDelivery oversized = nextAlive(1);
        assertThat(oversized.oversized()).as("one byte over").isTrue();
        assertThat(oversized.size()).isEqualTo(size);
        assertThat(feed().publishAsIs(alive)).isTrue();
        assertThat(nextAlive(2).oversized()).as("and the consumer goes on").isTrue();
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
        assertThat(session.skippedAcks()).isEqualTo(1);
        assertThat(session.queue().epochDiscards())
                .as("the one still queued when the channel was replaced")
                .isEqualTo(1);
        assertThat(transport.reconnects()).as("a reset is no reconnect").isZero();

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
        assertThat(transport.reconnects()).isEqualTo(1);
        assertThat(transport.connectionOpen()).isTrue();

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
        awaitReopened((SessionChannel) session, before);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());

        // and the SDK's own alive consumer
        awaitAnAlive();
        assertThat(events.events).as("the connection stayed up").containsExactly("connecting", "up");
    }

    @Test
    void aChannelThatWillNotOpenIsTriedAgainWithoutLeavingQueuesBehind() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        var channel = (SessionChannel) session;
        feed().removeExchange(FakeFeed.EXCHANGE);
        try {
            // the queue goes, and every new one fails to bind to the exchange that is gone
            feed().deleteClientQueues();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while ((channel.failedReopens() < 2 || alive(transport).failedReopens() < 2)
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(channel.failedReopens()).as("reopens that failed").isGreaterThanOrEqualTo(2);
            assertThat(alive(transport).failedReopens())
                    .as("alive reopens that failed")
                    .isGreaterThanOrEqualTo(2);
            assertThat(feed().clientQueues())
                    .as("a queue that could not be bound is deleted")
                    .hasSizeLessThanOrEqualTo(2);
        } finally {
            feed().restoreExchange(FakeFeed.EXCHANGE);
        }
        // open again: not "a later epoch", since an attempt under way may already have taken it
        awaitOpen(channel);
        assertThat(channel.failedReopens())
                .as("reset by the reopen that worked")
                .isZero();
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
        awaitAnAlive();
        assertThat(alive(transport).failedReopens())
                .as("reset by the reopen that worked")
                .isZero();
        assertThat(events.events).as("the connection stayed up").containsExactly("connecting", "up");
    }

    @Test
    void aReopenKeepsAChannelThatIsOpenAndReopensOneThatIsNot() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        // a reset that was under way, or a reconnect, opened a channel since the loss
        session.reset();
        long since = session.epoch();
        feed().publishFixture(ODDS_CHANGE);
        awaitSize(session, 1);

        assertThat(session.reopenIfLost()).isTrue();
        assertThat(session.epoch()).as("the open channel, kept").isEqualTo(since);
        assertThat(session.queue().size()).as("with its delivery").isEqualTo(1);
        assertThat(session.isOpen()).isTrue();

        // held off, as while reconnecting, so the loss is left to this reopen
        transport.reconnecting.set(true);
        feed().deleteClientQueues();
        awaitTaken(transport, session, false);
        assertThat(session.reopenIfLost()).isTrue();
        assertThat(session.epoch()).as("the channel taken, reopened").isEqualTo(since + 1);
        assertThat(session.isOpen()).isTrue();
        assertThat(session.queue().size()).isZero();
    }

    @Test
    void aChannelTakenWhileAReconnectOpensTheOthersIsReopenedWhenItHandsOver() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        var once = new AtomicBoolean();
        transport.afterChannelsOpen = () -> {
            if (once.compareAndSet(false, true)) {
                // taken while the reconnect still runs: its loss finds a reconnect, and leaves it
                feed().deleteClientQueues();
                awaitQuietly(() -> !session.isOpen() && !alive(transport).isOpen());
            }
        };

        feed().closeConnections();
        // the hook runs rabbitmqctl and waits inside the reconnect: more than the usual wait
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT.multipliedBy(3));
        assertThat(once).isTrue();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!session.isOpen() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.isOpen()).as("reopened by the hand-off").isTrue();
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
        awaitAnAlive();
    }

    @Test
    void aChannelTakenWhileAReopenHeldItsClaimIsReopenedWhenTheClaimEnds() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();
        assertThat(session.startReopen()).isTrue();
        assertThat(alive(transport).startReopen()).isTrue();

        feed().deleteClientQueues();
        awaitTaken(transport, session, true);
        Thread.sleep(500);
        assertThat(session.isOpen()).as("the loss found the claim held").isFalse();
        assertThat(alive(transport).isOpen()).isFalse();

        transport.released(session);
        transport.released(alive(transport));
        awaitReopened(session, before);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
        awaitAnAlive();
    }

    @Test
    void aConnectionLostAsTheFeedOpensOrAsItComesUpIsMadeAgain() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        SessionTransport session = transport.addSession(allKeys());
        var losses = new AtomicInteger();
        transport.afterChannelsOpen = () -> {
            // lost where the connection's listener ignores it: as the feed opens, then as it comes up
            if (losses.getAndIncrement() < 2) {
                feed().closeConnections();
                awaitQuietly(() -> !transport.connectionOpen());
            }
        };
        transport.open();
        events.await(e -> e.equals("up") && events.count("up") == 3, WAIT);
        assertThat(events.events)
                .containsExactly("connecting", "up", "down", "recovering", "up", "down", "recovering", "up");
        assertThat(transport.reconnects())
                .as("each connection made again counted once")
                .isEqualTo(2);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void aTransportClosedWhileItReconnectsTellsNoUpAndLeavesNothingOpen() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        transport.addSession(allKeys());
        transport.open();
        transport.afterChannelsOpen = () -> {
            Thread.ofVirtual().start(transport::close);
            awaitQuietly(transport::isClosed);
        };
        feed().closeConnections();
        events.await("recovering"::equals, WAIT);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!transport.isClosed() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(500);
        assertThat(events.count("up")).as("no up after the close").isEqualTo(1);
        deadline = System.nanoTime() + WAIT.toNanos();
        while (!feed().openConnections().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(feed().openConnections()).isEmpty();
    }

    @Test
    void anAliveHandlerThatThrowsKeepsTheAliveConsumer() throws Exception {
        var calls = new AtomicInteger();
        var transport = new AmqpTransport(settings(10, 1 << 20), FakeFeed.EXCHANGE, events, alive -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("the first alive fails");
            }
            events.alive(alive);
        });
        open.add(transport);
        transport.addSession(allKeys());
        transport.open();
        awaitAnAlive();
        assertThat(alive(transport).handlerFailures()).isEqualTo(1);
        assertThat(alive(transport).isOpen()).isTrue();
    }

    @Test
    void aBrokerWhoseCertificateIsNotTrustedIsRefused() throws Exception {
        var onlyTheJdksTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        onlyTheJdksTrust.init((KeyStore) null);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, onlyTheJdksTrust.getTrustManagers(), null);
        AmqpSettings base = settings(10, 1 << 20);
        assertRefusedByTls(withTls(base, base.host(), tls), "certification path");
    }

    @Test
    void withNoTrustGivenTheJvmsDefaultIsWhatChecksTheCertificate() throws Exception {
        AmqpSettings base = settings(10, 1 << 20);
        SSLContext before = SSLContext.getDefault();
        try {
            // the JVM default trusts the fake broker in these tests: then the feed connects
            var trusted = transport(withTls(base, base.host(), null), false);
            trusted.addSession(allKeys());
            trusted.open();
            trusted.close();

            // and with only the JDK's trust as the default, the same settings are refused
            var onlyTheJdksTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            onlyTheJdksTrust.init((KeyStore) null);
            SSLContext jdk = SSLContext.getInstance("TLS");
            jdk.init(null, onlyTheJdksTrust.getTrustManagers(), null);
            SSLContext.setDefault(jdk);
            assertRefusedByTls(withTls(base, base.host(), null), "certification path");
        } finally {
            SSLContext.setDefault(before);
        }
    }

    @Test
    void aHostTheCertificateDoesNotNameIsRefused() throws Exception {
        // a broker whose trusted certificate names another host, dialled as the host it is
        var identity = TestTls.namingOnly("feed.example.invalid");
        try (FakeFeed other = FakeFeed.start(identity.pem())) {
            AmqpSettings base = settings(other, 10, 1 << 20);
            assertRefusedByTls(other, withTls(base, base.host(), identity.trusting()), "subject alternative");
        }
    }

    @Test
    void aDeliveryRefusedForWantOfRoomIsAcknowledgedSoTheSessionGoesOn() throws Exception {
        AmqpTransport transport = transport(settings(3, 1 << 20), false);
        SessionTransport session = transport.addSession(allKeys(), 1);
        transport.open();
        for (int i = 0; i < 5; i++) {
            feed().publishFixture(ODDS_CHANGE);
        }
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (session.queue().overflowed() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.queue().overflowed()).as("room for one only").isEqualTo(4);
        session.ack(next(session));
        feed().publishFixture(ODDS_CHANGE);
        assertThat(next(session)).as("the refused ones gave their credit back").isNotNull();
    }

    @Test
    void aBrokerAtItsConnectionLimitIsRetriedWithTheLongPauseAndSaysSo() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        transport.addSession(allKeys());
        transport.open();
        feed().limitConnections(0);
        try {
            feed().closeConnections();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (events.reasons.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(events.reasons.get(1)).contains("limit");
            assertThat(events.waits.get(1)).as("the resource pause").isEqualTo(1_000L);
            assertThat(String.join(" ", events.told))
                    .doesNotContain("test-token")
                    .contains(Failure.TOKEN);
        } finally {
            feed().limitConnections(-1);
        }
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
    }

    @Test
    void anOpenTheBrokerTurnsAwayDoesNotTellTheToken() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        transport.addSession(allKeys());
        feed().limitConnections(0);
        try {
            assertThatThrownBy(transport::open)
                    .isInstanceOf(InitException.class)
                    .hasMessageContaining("is out of resources")
                    .satisfies(e -> assertThat(chain(e))
                            .allSatisfy(cause -> assertThat(String.valueOf(cause.getMessage()))
                                    .doesNotContain("test-token"))
                            .anySatisfy(cause -> assertThat(String.valueOf(cause.getMessage()))
                                    .contains(Failure.TOKEN)));
        } finally {
            feed().limitConnections(-1);
        }
    }

    @Test
    void aTransportClosedAsItOpensFailsTheOpenAndTellsNoUp() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        transport.addSession(allKeys());
        transport.afterChannelsOpen = () -> {
            Thread.ofVirtual().start(transport::close);
            awaitQuietly(transport::isClosed);
        };
        assertThatThrownBy(transport::open)
                .isInstanceOf(InitException.class)
                .hasMessageContaining("closed as it opened");
        assertThat(events.events).containsExactly("connecting");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!feed().openConnections().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(feed().openConnections()).isEmpty();
    }

    @Test
    void aResetAfterTheChannelClosedOpensNothing() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();
        // a reset handed to a worker before the session closed, run after it
        session.close();
        session.reset();
        assertThat(session.channel()).as("a channel nobody reads").isNull();
        assertThat(session.isOpen()).isFalse();
        assertThat(session.epoch()).isEqualTo(before);
        assertThat(session.reopenIfLost()).isTrue();
        assertThat(session.channel()).isNull();
    }

    @Test
    void aResetAfterTheTransportClosedMovesNoEpoch() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();
        // the close aborts the connection, then closes the channels
        transport.close();
        session.reset();
        assertThat(session.epoch()).as("the epoch of a channel closed for good").isEqualTo(before);
        assertThat(session.channel()).isNull();
    }

    @Test
    void aResetBetweenTheClosesAbortAndTheChannelsCloseMovesNoEpochAndKeepsTheDeliveries() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        feed().publishFixture(ODDS_CHANGE);
        feed().publishFixture(ODDS_CHANGE);
        awaitSize(session, 2);
        long before = session.epoch();
        // the connection is cut, the channel not closed yet: a reset handed to a worker runs now
        transport.afterAbort = session::reset;
        transport.close();
        assertThat(session.epoch()).as("nothing was replaced").isEqualTo(before);
        assertThat(session.queue().size())
                .as("what the session had taken stays for it")
                .isEqualTo(2);
        assertThat(session.channel()).isNull();
    }

    @Test
    void aChannelClosedForGoodOpensNothingOnAConnectionItIsHanded() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();
        session.close();
        Connection other = transport.factory().newConnection();
        try {
            // an open after the close: the transport's own guard, its lock, keeps it from coming
            session.open(other);
            assertThat(session.channel()).as("no channel opened").isNull();
            assertThat(session.isOpen()).isFalse();
            assertThat(session.epoch()).isEqualTo(before);
        } finally {
            other.abort();
        }
    }

    @Test
    void closeDoesNotWaitForAReconnectsHandshake() throws Exception {
        // a connect timeout far longer than close() may take; the heartbeat notices the frozen broker
        AmqpSettings slow = withTimeouts(settings(10, 1 << 20), Duration.ofSeconds(1), Duration.ofSeconds(30));
        AmqpTransport transport = transport(slow, false);
        transport.addSession(allKeys());
        transport.open();
        feed().pause();
        try {
            events.await("recovering"::equals, Duration.ofSeconds(15));
            // past the first pause, so the reconnect waits on a broker that does not answer
            Thread.sleep(1_000);
            long closing = System.nanoTime();
            transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - closing))
                    .as("close() while a reconnect waits for the broker's handshake")
                    .isLessThan(Duration.ofSeconds(5));
        } finally {
            feed().resume();
        }
        Thread.sleep(500);
        assertThat(events.count("up")).as("no up after the close").isEqualTo(1);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!feed().openConnections().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(feed().openConnections())
                .as("the connection the reconnect made is cut")
                .isEmpty();
    }

    @Test
    void closeDoesNotWaitForTheFirstConnectsHandshake() throws Exception {
        try (var silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var accepted = new CompletableFuture<Socket>();
            Thread.ofVirtual().start(() -> {
                try {
                    accepted.complete(silent.accept());
                } catch (java.io.IOException e) {
                    accepted.completeExceptionally(e);
                }
            });
            var base = settings(10, 1 << 20);
            // a broker that takes the connection and never answers the handshake, within a connect
            // timeout far longer than close() may take
            var unanswered = new AmqpSettings(
                    "127.0.0.1",
                    silent.getLocalPort(),
                    base.virtualHost(),
                    base.accessToken(),
                    base.tls(),
                    base.connectionName(),
                    base.prefetch(),
                    base.maxMessageSize(),
                    base.heartbeat(),
                    Duration.ofSeconds(30),
                    base.firstBackoff(),
                    base.maxBackoff(),
                    base.resourceBackoff());
            AmqpTransport transport = transport(unanswered, false);
            transport.addSession(allKeys());
            var opening = CompletableFuture.runAsync(transport::open);
            Socket socket = accepted.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            try {
                long closing = System.nanoTime();
                transport.close();
                assertThat(Duration.ofNanos(System.nanoTime() - closing))
                        .as("close() while the first connect waits for the broker's handshake")
                        .isLessThan(Duration.ofSeconds(5));
            } finally {
                socket.close();
            }
            // the broker hangs up, so the handshake ends; the open finds the transport closed
            assertThatThrownBy(() -> opening.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(InitException.class)
                    .hasMessageContaining("closed as it opened");
            assertThat(events.events).containsExactly("connecting");
        }
    }

    @Test
    void aFirstConnectThatSucceedsAfterCloseIsCutBeforeAnyChannelOpens() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        var session = (SessionChannel) transport.addSession(allKeys());
        long before = session.epoch();
        long aliveBefore = alive(transport).epoch();
        // a close that comes as the connect ends: marked closed, held before it cuts or closes anything
        var closing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        transport.beforeAbort = () -> {
            closing.countDown();
            awaitQuietly(() -> release.getCount() == 0);
        };
        var closer = new AtomicReference<@Nullable Thread>();
        transport.afterConnect = () -> {
            closer.set(Thread.ofVirtual().start(transport::close));
            awaitQuietly(() -> closing.getCount() == 0);
        };
        try {
            assertThatThrownBy(transport::open)
                    .isInstanceOf(InitException.class)
                    .hasMessageContaining("closed as it opened");
        } finally {
            release.countDown();
        }
        requireNonNull(closer.get()).join(WAIT);

        assertThat(session.epoch()).as("no channel opened on the connection").isEqualTo(before);
        assertThat(alive(transport).epoch()).as("nor the alive channel").isEqualTo(aliveBefore);
        assertThat(events.events).as("never told up").containsExactly("connecting");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!feed().openConnections().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(feed().openConnections())
                .as("the connection made after the close is cut")
                .isEmpty();
    }

    @Test
    void closeDoesNotWaitForAConsumerCallbackThatIsStillRunning() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transport = new AmqpTransport(settings(10, 1 << 20), FakeFeed.EXCHANGE, events, alive -> {
            entered.countDown();
            try {
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        open.add(transport);
        transport.addSession(allKeys());
        transport.open();
        try {
            feed().publishFixture("feed/alive/alive.xml");
            assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            long closing = System.nanoTime();
            transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - closing))
                    .as("close() with the alive handler running on the consumer thread")
                    .isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
        }
    }

    @Test
    void theWatchdogSeesAConsumerHandOffThatHasNotReturned() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transport = new AmqpTransport(settings(10, 1 << 20), FakeFeed.EXCHANGE, events, alive -> {
            entered.countDown();
            try {
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        open.add(transport);
        transport.addSession(allKeys());
        assertThat(transport.consumerState())
                .as("no consumer thread before the open")
                .isEqualTo(new AmqpTransport.ConsumerState(0, 0, 0));
        transport.open();
        long published = System.currentTimeMillis();
        feed().publishFixture("feed/alive/alive.xml");
        assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        var wedged = transport.consumerState();
        assertThat(wedged.busySince())
                .as("the alive's hand-off, running")
                .isBetween(published, System.currentTimeMillis());

        long taken = wedged.taken();
        release.countDown();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (transport.consumerState().busySince() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        var returned = transport.consumerState();
        assertThat(returned.busySince()).as("nothing running").isZero();
        assertThat(returned.taken()).as("the hand-off ran to its end").isGreaterThan(taken);
        assertThat(returned.waiting()).isZero();
    }

    @Test
    void aResetOnceTheCloseHasBegunAndBeforeItsAbortMovesNoEpochAndKeepsTheDeliveries() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        feed().publishFixture(ODDS_CHANGE);
        feed().publishFixture(ODDS_CHANGE);
        awaitSize(session, 2);
        long before = session.epoch();
        // the transport closes, its connection still open: a reset handed to a worker runs now
        transport.beforeAbort = session::reset;
        transport.close();
        assertThat(session.epoch()).as("nothing was replaced").isEqualTo(before);
        assertThat(session.queue().size())
                .as("what the session had taken stays for it")
                .isEqualTo(2);
        assertThat(session.channel()).isNull();
    }

    @Test
    void aResetThatCannotOpenTheChannelLeavesItToTheReopenLoop() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        feed().removeExchange(FakeFeed.EXCHANGE);
        try {
            session.reset();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (session.failedReopens() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(session.failedReopens())
                    .as("the reset's failure, and the loop's")
                    .isGreaterThanOrEqualTo(2);
        } finally {
            feed().restoreExchange(FakeFeed.EXCHANGE);
        }
        awaitOpen(session);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void theClientReportsCallbackFailuresWithoutTheTokenAndClosesNothing() {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var factory = transport.factory();
        assertThat(factory.getExceptionHandler()).isInstanceOf(RedactingExceptionHandler.class);
        assertThat(factory.getChannelRpcTimeout()).isEqualTo(5_000);
    }

    @Test
    void aResetWhileTheConnectionIsDownMovesTheEpochAndLeavesTheOpeningToTheReconnect() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        feed().publishFixture(ODDS_CHANGE);
        feed().publishFixture(ODDS_CHANGE);
        awaitSize(session, 2);
        RawDelivery old = next(session);
        long resetIn;
        feed().limitConnections(0);
        try {
            feed().closeConnections();
            events.await("down"::equals, WAIT);
            long before = session.epoch();
            session.reset();
            resetIn = session.epoch();
            assertThat(resetIn).isEqualTo(before + 1);
            assertThat(session.queue().size())
                    .as("the old epoch's deliveries are out")
                    .isZero();
            session.ack(old);
            assertThat(session.skippedAcks()).isEqualTo(1);
            assertThat(session.isOpen()).isFalse();
            assertThat(session.failedReopens()).as("no reopen of its own").isZero();
        } finally {
            feed().limitConnections(-1);
        }
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
        RawDelivery after = deliveredAfterPublishing(session);
        assertThat(after.epoch()).as("opened by the reconnect").isGreaterThan(resetIn);
    }

    @Test
    void aChannelTheBrokerClosesIsOpenedAgainOnTheLiveConnection() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();

        // an acknowledgement of a tag the broker never sent: it closes the channel, not the connection
        session.ack(new RawDelivery(new byte[0], 0, "-", 9_999, before, Instant.now(), null));
        awaitReopened(session, before);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());

        var alive = requireNonNull(alive(transport).channel());
        alive.basicAck(9_999, false);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while ((alive.isOpen() || !alive(transport).isOpen()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(alive(transport).channel()).as("a new alive channel").isNotSameAs(alive);
        awaitAnAlive();
        assertThat(events.events).as("the connection stayed up").containsExactly("connecting", "up");
    }

    @Test
    void aSessionIsToldOfALostChannelBeforeTheNewOneAndOfTheNewOneOnceItIsBound() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), true);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();

        // the broker cancels the consumer: its queue is gone
        feed().deleteClientQueues();
        awaitReopened(session, before);
        told.await(2);
        assertThat(told.events)
                .as("the loss told in the lost channel's epoch, the new channel once it reads")
                .containsExactly("lost in " + before, "reopened in " + session.epoch() + ", open");
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());

        // the broker closes the channel this time; each loss is told
        long second = session.epoch();
        session.ack(new RawDelivery(new byte[0], 0, "-", 9_999, second, Instant.now(), null));
        awaitReopened(session, second);
        told.await(4);
        assertThat(told.events.subList(2, 4))
                .containsExactly("lost in " + second, "reopened in " + session.epoch() + ", open");
        awaitAnAlive();
        assertThat(told.events)
                .as("the alive channel's loss is not the session's")
                .hasSize(4);
        assertThat(events.events).as("the connection stayed up").containsExactly("connecting", "up");
    }

    @Test
    void aLossIsToldOnceWhileItsReopenFailsAndTheNewChannelOnlyOnceItIsBound() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        feed().removeExchange(FakeFeed.EXCHANGE);
        try {
            // the queue goes, and every new one fails to bind to the exchange that is gone
            feed().deleteClientQueues();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (session.failedReopens() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(session.failedReopens()).as("reopens that failed").isGreaterThanOrEqualTo(2);
            assertThat(told.events).as("nothing bound yet").containsExactly("lost in " + before);
        } finally {
            feed().restoreExchange(FakeFeed.EXCHANGE);
        }
        awaitOpen(session);
        told.await(2);
        assertThat(told.events).containsExactly("lost in " + before, "reopened in " + session.epoch() + ", open");
    }

    @Test
    void aLossToldIsFollowedByTheNewChannelWhenAReconnectOpensIt() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        feed().removeExchange(FakeFeed.EXCHANGE);
        try {
            // the queue goes, and its reopens fail to bind to the exchange that is gone
            feed().deleteClientQueues();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (session.failedReopens() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(told.events).containsExactly("lost in " + before);
            feed().closeConnections();
            events.await("down"::equals, WAIT);
        } finally {
            feed().restoreExchange(FakeFeed.EXCHANGE);
        }
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
        told.await(2);
        assertThat(told.events).containsExactly("lost in " + before, "reopened in " + session.epoch() + ", open");
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void aLossToldIsFollowedByTheNewChannelWhenTheCallersResetOpensIt() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        // the claim held, so the transport's own reopen leaves the loss to the reset
        assertThat(session.startReopen()).isTrue();
        feed().deleteClientQueues();
        awaitTaken(transport, session, false);
        told.await(1);

        session.reset();
        assertThat(told.events).containsExactly("lost in " + before, "reopened in " + session.epoch() + ", open");
        transport.released(session);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
        assertThat(told.events).as("told once").hasSize(2);
    }

    @Test
    void aLossToldStaysToldThroughAResetWithNoConnectionUntilTheReconnectOpensTheChannel() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        assertThat(session.startReopen()).isTrue();
        feed().deleteClientQueues();
        awaitTaken(transport, session, false);
        told.await(1);
        feed().limitConnections(0);
        try {
            feed().closeConnections();
            events.await("down"::equals, WAIT);
            // no connection to open one on: the epoch moves, and nothing is bound yet
            session.reset();
            assertThat(session.epoch()).isGreaterThan(before);
            assertThat(told.events).containsExactly("lost in " + before);
        } finally {
            feed().limitConnections(-1);
        }
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
        told.await(2);
        assertThat(told.events).containsExactly("lost in " + before, "reopened in " + session.epoch() + ", open");
        transport.released(session);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void aLossTheReopenGetsToBeforeItsCallbackIsToldByTheReopen() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        var reopen = new ReopenFirst(session, told);
        // the callback saw its channel taken, and a reopen runs before it tells the loss
        session.beforeTellingLost = reopen;

        feed().deleteClientQueues();
        reopen.assertToldByTheReopen(before);
        Thread.sleep(200);
        assertThat(told.events).as("the callback told nothing more").hasSize(2);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void aChannelTheBrokerClosedIsToldLostByAReopenThatRunsBeforeItsCallback() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        var reopen = new ReopenFirst(session, told);
        // the client has marked the channel closed, and a reopen runs before the callback even starts
        session.beforeTaken = reopen;

        requireNonNull(session.channel()).basicAck(9_999, false);
        reopen.assertToldByTheReopen(before);
        Thread.sleep(200);
        assertThat(told.events).as("the callback told nothing more").hasSize(2);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void aLossWhoseChannelAResetReplacedBeforeItWasToldIsNotTold() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        var once = new AtomicBoolean();
        // the callback saw its channel taken, and the caller's reset replaces it before it tells
        session.beforeTellingLost = () -> {
            if (once.compareAndSet(false, true)) {
                runAndWait(session::reset);
            }
        };

        feed().deleteClientQueues();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!once.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(once).as("the reset ran in the callback").isTrue();
        assertThat(deliveredAfterPublishing(session).epoch())
                .isEqualTo(session.epoch())
                .isGreaterThan(before);
        assertThat(told.events).isEmpty();
    }

    @Test
    void aChannelCancelledAndThenClosedIsToldLostOnce() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        long before = session.epoch();
        // the claim held, so the channel stays as the broker leaves it
        assertThat(session.startReopen()).isTrue();
        feed().deleteClientQueues();
        told.await(1);

        // the broker closes the same channel too: an acknowledgement of a tag it never sent
        var channel = requireNonNull(session.channel());
        channel.basicAck(9_999, false);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (channel.isOpen() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(channel.isOpen()).isFalse();
        Thread.sleep(200);
        assertThat(told.events).containsExactly("lost in " + before);

        transport.released(session);
        awaitReopened(session, before);
        told.await(2);
        assertThat(told.events).containsExactly("lost in " + before, "reopened in " + session.epoch() + ", open");
    }

    @Test
    void aLossSeenAsTheChannelClosesIsNotTold() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();
        var closedFirst = new AtomicBoolean();
        // the callback found its channel current, and the transport closes the channel before it tells
        session.beforeTellingLost = () -> {
            Thread closing = Thread.ofVirtual().start(session::close);
            try {
                closing.join(WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closedFirst.set(!closing.isAlive());
        };

        feed().deleteClientQueues();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!closedFirst.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(closedFirst).as("closed while the callback waited").isTrue();
        Thread.sleep(200);
        assertThat(told.events).isEmpty();
    }

    @Test
    void aLostConnectionAndAResetAreNotToldAsALostChannel() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var told = new ToldChannel();
        var session = (SessionChannel) transport.addSession(allKeys(), told);
        told.session = session;
        transport.open();

        // the caller's own doing, and the connection's event
        session.reset();
        feed().closeConnections();
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
        assertThat(told.events).isEmpty();
    }

    @Test
    void aChannelListenerThatThrowsDoesNotKeepTheChannelFromItsReopen() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys(), new ChannelEvents() {
            @Override
            public void lost() {
                throw new IllegalStateException("the listener failed");
            }
        });
        transport.open();
        long before = session.epoch();

        feed().deleteClientQueues();
        awaitReopened(session, before);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
    }

    @Test
    void aTransportClosedWhileItWaitsToTryAgainTellsNothingMore() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        transport.addSession(allKeys());
        transport.open();
        feed().limitConnections(0);
        try {
            feed().closeConnections();
            // refused for want of room, and waiting its pause to try again
            events.await(e -> e.equals("recovering") && events.count("recovering") == 2, WAIT);
            transport.close();
            List<String> atTheClose = List.copyOf(events.events);
            Thread.sleep(1_500);
            assertThat(events.events).as("nothing after close() returned").isEqualTo(atTheClose);
        } finally {
            feed().limitConnections(-1);
        }
    }

    @Test
    void aListenerThatThrowsDoesNotBreakTheTransport() throws Exception {
        ConnectionEvents throwingOnUp = new ConnectionEvents() {
            @Override
            public void connecting() {
                events.connecting();
            }

            @Override
            public void up() {
                events.up();
                throw new IllegalStateException("the listener failed");
            }

            @Override
            public void down(String reason) {
                events.down(reason);
            }

            @Override
            public void recovering(int attempt, long waitMillis, String reason) {
                events.recovering(attempt, waitMillis, reason);
            }

            @Override
            public void fatal(String reason, @Nullable Throwable cause) {
                events.fatal(reason, cause);
            }
        };
        var transport = new AmqpTransport(settings(10, 1 << 20), FakeFeed.EXCHANGE, throwingOnUp, null);
        open.add(transport);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        feed().closeConnections();
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
        Thread.sleep(500);
        assertThat(events.count("up")).as("the connection it made was kept").isEqualTo(2);
    }

    @Test
    void aChannelListenerThatThrowsOnTheNewChannelBreaksNeitherTheReopenNorTheReconnect() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        var session = (SessionChannel) transport.addSession(allKeys(), new ChannelEvents() {
            @Override
            public void reopened() {
                throw new IllegalStateException("the listener failed");
            }
        });
        transport.open();
        long before = session.epoch();

        feed().deleteClientQueues();
        awaitReopened(session, before);
        assertThat(session.failedReopens()).as("the reopen counts as made").isZero();
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());

        // a loss the reconnect replaces
        assertThat(session.startReopen()).isTrue();
        feed().deleteClientQueues();
        awaitTaken(transport, session, false);
        feed().closeConnections();
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT);
        transport.released(session);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
        assertThat(events.events)
                .as("one up for the one lost connection")
                .containsExactly("connecting", "up", "down", "recovering", "up");
    }

    @Test
    void closeCutsOffADeclareTheBrokerDoesNotAnswer() throws Exception {
        // a long timeout; the fake broker's heartbeat of 2 s would end the stalled call within about
        // 4 s, so a close that waited for it would take that long
        AmqpSettings slow = withTimeouts(settings(10, 1 << 20), Duration.ofSeconds(60), Duration.ofSeconds(20));
        AmqpTransport transport = transport(slow, false);
        var session = (SessionChannel) transport.addSession(allKeys());
        transport.open();
        feed().pause();
        try {
            var reset = Thread.ofVirtual().start(session::reset);
            Thread.sleep(500);
            long closing = System.nanoTime();
            transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - closing))
                    .as("close() with a channel waiting on the broker")
                    .isLessThan(Duration.ofSeconds(2));
            reset.join(5_000);
            assertThat(reset.isAlive()).isFalse();
        } finally {
            feed().resume();
        }
    }

    @Test
    void aBrokerThatStopsAnsweringIsNoticedByTheHeartbeatAndReconnected() throws Exception {
        AmqpTransport transport = transport(settings(10, 1 << 20), false);
        SessionTransport session = transport.addSession(allKeys());
        transport.open();
        long before = session.epoch();
        feed().pause();
        try {
            // frozen with the connection open, as a network that drops everything looks
            events.await("down"::equals, Duration.ofSeconds(15));
            assertThat(events.told).anySatisfy(reason -> assertThat(reason).containsIgnoringCase("heartbeat"));
        } finally {
            feed().resume();
        }
        events.await(e -> e.equals("up") && events.count("up") == 2, WAIT.multipliedBy(3));
        assertThat(session.epoch()).isGreaterThan(before);
        assertThat(deliveredAfterPublishing(session).epoch()).isEqualTo(session.epoch());
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
        assertThat(events.events).as("and no reconnect").containsExactly("connecting");
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
        assertRefusedByTls(feed(), settings, why);
    }

    private void assertRefusedByTls(FakeFeed broker, AmqpSettings settings, String why) {
        int loginsBefore = broker.logins().size();
        var transport = new AmqpTransport(settings, FakeFeed.EXCHANGE, events, null);
        open.add(transport);
        transport.addSession(allKeys());
        assertThatThrownBy(transport::open)
                .isInstanceOf(InitException.class)
                .hasMessageContaining("could not be trusted")
                .satisfies(e -> assertThat(chain(e))
                        .as("the cause chain")
                        .anySatisfy(cause -> assertThat(String.valueOf(cause.getMessage()))
                                .containsAnyOf("SSLHandshakeException", "CertificateException"))
                        .anySatisfy(cause ->
                                assertThat(String.valueOf(cause.getMessage())).contains(why)));
        assertThat(broker.logins()).as("no login got as far as the broker").hasSize(loginsBefore);
    }

    private static List<Throwable> chain(Throwable failure) {
        var chain = new ArrayList<Throwable>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private static AmqpSettings withTls(AmqpSettings base, String host, @Nullable SSLContext tls) {
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

    private static SessionChannel alive(AmqpTransport transport) {
        return requireNonNull(transport.aliveChannel());
    }

    private static List<String> allKeys() {
        return RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true);
    }

    private static RawDelivery next(SessionTransport session) throws InterruptedException {
        RawDelivery delivery = session.queue().poll(WAIT);
        assertThat(delivery).as("a delivery within " + WAIT).isNotNull();
        return requireNonNull(delivery);
    }

    /** Waits until the broker took the session's channel, and the alive channel with it. */
    private static void awaitTaken(AmqpTransport transport, SessionChannel session, boolean alive)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while ((session.isOpen() || (alive && alive(transport).isOpen())) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.isOpen()).as("taken").isFalse();
    }

    /** Waits, from a hook that cannot throw, until {@code done} or the limit. */
    private static void awaitQuietly(BooleanSupplier done) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The SDK's own consumer's alive at this index, once it is there. */
    private RawDelivery nextAlive(int index) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (events.alives.size() <= index && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(events.alives).hasSizeGreaterThan(index);
        return events.alives.get(index);
    }

    /** Publishes alives until the SDK's own consumer gets one more. */
    private void awaitAnAlive() throws InterruptedException {
        int before = events.alives.size();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (events.alives.size() == before && System.nanoTime() < deadline) {
            feed().publishFixture("feed/alive/alive.xml");
            Thread.sleep(200);
        }
        assertThat(events.alives).as("an alive to the SDK's own consumer").hasSizeGreaterThan(before);
    }

    /** Waits for the session's channel to be open. */
    private static void awaitOpen(SessionChannel session) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!session.isOpen() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.isOpen()).as("the session's channel, open").isTrue();
    }

    private static AmqpSettings withTimeouts(AmqpSettings base, Duration heartbeat, Duration connectTimeout) {
        return new AmqpSettings(
                base.host(),
                base.port(),
                base.virtualHost(),
                base.accessToken(),
                base.tls(),
                base.connectionName(),
                base.prefetch(),
                base.maxMessageSize(),
                heartbeat,
                connectTimeout,
                base.firstBackoff(),
                base.maxBackoff(),
                base.resourceBackoff());
    }

    /** Waits for the session's channel to be open again in an epoch after {@code before}. */
    private static void awaitReopened(SessionChannel session, long before) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while ((session.epoch() == before || !session.isOpen()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(session.epoch()).as("the session's channel, opened again").isGreaterThan(before);
        assertThat(session.isOpen()).isTrue();
    }

    /** Publishes until the broker routes it: a binding can come a moment after the channel is open. */
    private static RawDelivery deliveredAfterPublishing(SessionTransport session) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!feed().publishFixture(ODDS_CHANGE) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return next(session);
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

    /** Runs {@code action} on a thread of its own and waits for it, from a hook that cannot throw. */
    private static void runAndWait(Runnable action) {
        Thread running = Thread.ofVirtual().start(action);
        try {
            running.join(WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A hook that, the first time it runs, has the transport's reopen run on a thread of its own and
     * waits for it, and keeps what the reopen returned and what was told by then.
     */
    private static final class ReopenFirst implements Runnable {
        private final SessionChannel session;
        private final ToldChannel told;
        private final AtomicBoolean ran = new AtomicBoolean();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean finished;
        private volatile boolean reopened;
        private volatile List<String> toldByThen = List.of();

        ReopenFirst(SessionChannel session, ToldChannel told) {
            this.session = session;
            this.told = told;
        }

        @Override
        public void run() {
            if (!ran.compareAndSet(false, true)) {
                return;
            }
            var result = new AtomicBoolean();
            Thread reopening = Thread.ofVirtual().start(() -> result.set(session.reopenIfLost()));
            try {
                reopening.join(WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished = !reopening.isAlive();
            reopened = result.get();
            toldByThen = List.copyOf(told.events);
            done.countDown();
        }

        /** The reopen finished before the callback went on, and told the loss and its new channel. */
        void assertToldByTheReopen(long before) throws InterruptedException {
            assertThat(done.await(WAIT.toSeconds() * 2, TimeUnit.SECONDS))
                    .as("the hook ran")
                    .isTrue();
            assertThat(finished)
                    .as("the reopen finished before the callback went on")
                    .isTrue();
            assertThat(reopened).as("what the reopen returned").isTrue();
            assertThat(toldByThen)
                    .as("told by the reopen, before the callback went on")
                    .containsExactly("lost in " + before, "reopened in " + (before + 1) + ", open");
        }
    }

    /** What a session was told of its channel, with the epoch and state it had then. */
    private static final class ToldChannel implements ChannelEvents {
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile @Nullable SessionChannel session;

        @Override
        public void lost() {
            events.add("lost in " + requireNonNull(session).epoch());
        }

        @Override
        public void reopened() {
            SessionChannel channel = requireNonNull(session);
            events.add("reopened in " + channel.epoch() + (channel.isOpen() ? ", open" : ", not open"));
        }

        /** Waits until {@code count} events were told, for up to the test's wait. */
        void await(int count) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (events.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(events).as("told").hasSizeGreaterThanOrEqualTo(count);
        }
    }
}
