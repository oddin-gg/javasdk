package com.oddin.oddsfeedsdk.internal.feed;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpTransport.ChannelRefusals;
import com.oddin.oddsfeedsdk.internal.catalog.CatalogHealth;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.rest.ApiCall;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.session.SessionRegistry;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Each part's state as the health finds it, and each change told once, in order: a session lagging,
 * a catalog stale past its limit, the connection down past its limit, what the SDK's own watch
 * found.
 */
class HealthMonitorTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final OddsFeedSession first = new OddsFeedSession() {};
    private final Heard heard = new Heard();
    private final EventsDispatcher events = new EventsDispatcher(heard, null, id -> null, id -> null);
    /** The changes the health logged, in order. */
    private final List<HealthEvent> logged = new CopyOnWriteArrayList<>();

    private final HealthMonitor health =
            new HealthMonitor(events, id -> id == 1 ? first : null, InstantSource.fixed(NOW), HOUR, logged::add);

    @AfterEach
    void close() {
        events.close();
    }

    @Test
    void aSessionLaggingIsDegradedAndToldAtOnceAndCatchingUpIsToldAgain() throws InterruptedException {
        events.start();
        health.lagging(1, true);
        var degraded = heard.next();
        assertThat(degraded.component()).isEqualTo(HealthComponent.SESSION);
        assertThat(degraded.session()).isSameAs(first);
        assertThat(degraded.previous()).isEqualTo(HealthState.HEALTHY);
        assertThat(degraded.state()).isEqualTo(HealthState.DEGRADED);
        assertThat(degraded.reason()).contains("session 1 is lagging");
        assertThat(degraded.at()).isEqualTo(NOW);

        health.lagging(1, true);
        var reading = read(session(1, true), session(2, false));
        assertThat(reading.sessions())
                .extracting(FeedHealth.Session::state)
                .containsExactly(HealthState.DEGRADED, HealthState.HEALTHY);
        assertThat(reading.components()).containsEntry(HealthComponent.SESSION, HealthState.DEGRADED);
        assertThat(reading.state()).isEqualTo(HealthState.DEGRADED);

        health.lagging(1, false);
        var caughtUp = heard.next();
        assertThat(caughtUp.previous()).isEqualTo(HealthState.DEGRADED);
        assertThat(caughtUp.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(caughtUp.reason()).contains("session 1 is keeping up");
        heard.nothingMore("lagging told twice and the readings: one change");
    }

    @Test
    void aCatalogIsDegradedOnceItHasServedAValueStaleForItsLimit() throws InterruptedException {
        events.start();
        var justUnder = read(List.of(catalog("void reasons", HOUR.minusSeconds(1)), catalog("match statuses", 0)));
        assertThat(justUnder.catalogs())
                .extracting(FeedHealth.Catalog::state)
                .containsExactly(HealthState.HEALTHY, HealthState.HEALTHY);
        assertThat(justUnder.components()).containsEntry(HealthComponent.CATALOGS, HealthState.HEALTHY);
        heard.nothingMore("a catalog stale for less than the limit");

        var stale = read(List.of(
                catalog("void reasons", HOUR), catalog("match statuses", 0), catalog("market descriptions", 3 * 3600)));
        assertThat(stale.catalogs())
                .extracting(FeedHealth.Catalog::state)
                .containsExactly(HealthState.DEGRADED, HealthState.HEALTHY, HealthState.DEGRADED);
        assertThat(stale.catalogs().getFirst().staleFor()).isEqualTo(HOUR);
        assertThat(stale.components()).containsEntry(HealthComponent.CATALOGS, HealthState.DEGRADED);
        assertThat(stale.state()).isEqualTo(HealthState.DEGRADED);
        var degraded = heard.next();
        assertThat(degraded.component()).isEqualTo(HealthComponent.CATALOGS);
        assertThat(degraded.session()).isNull();
        assertThat(degraded.state()).isEqualTo(HealthState.DEGRADED);
        assertThat(degraded.reason())
                .as("named after the stalest")
                .isEqualTo("the market descriptions have served a value stale for 3 h 0 min, its refreshes failing");

        read(List.of(catalog("void reasons", 2 * 3600)));
        heard.nothingMore("still degraded");
        read(List.of(catalog("void reasons", 0)));
        var fresh = heard.next();
        assertThat(fresh.previous()).isEqualTo(HealthState.DEGRADED);
        assertThat(fresh.state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void aConnectionDownForLessThanASecondReadsItsTimeAndItsLimitInFull() throws InterruptedException {
        var nanos = new AtomicLong(1_000);
        var health = new HealthMonitor(
                events, id -> null, InstantSource.fixed(NOW), nanos::get, HOUR, Duration.ofMillis(250), logged::add);
        events.start();
        health.down("connection reset");
        nanos.addAndGet(Duration.ofMillis(300).toNanos());
        read(health);
        assertThat(heard.next().reason())
                .isEqualTo("the broker connection has been down for 0.3 s, over its limit of 0.25 s");
    }

    @Test
    void aConnectionLostThenClosedWithinItsLimitIsNeverDegraded() throws InterruptedException {
        var nanos = new AtomicLong(1_000);
        var limit = Duration.ofSeconds(60);
        var health =
                new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), nanos::get, HOUR, limit, logged::add);
        events.start();
        health.down("connection reset");
        nanos.addAndGet(Duration.ofSeconds(10).toNanos());
        health.closed();
        // what closing the transport leaves: neither up nor down told
        nanos.addAndGet(limit.multipliedBy(10).toNanos());
        assertThat(read(health).components())
                .as("long after the close")
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        health.down("connection closed");
        health.up();
        nanos.addAndGet(limit.multipliedBy(10).toNanos());
        assertThat(read(health).components()).containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        heard.nothingMore("after the close");
        assertThat(logged).as("logged").isEmpty();
    }

    @Test
    void aConnectionPastItsLimitUnreadAtTheCloseReadsDegradedButIsToldNoMore() throws InterruptedException {
        var nanos = new AtomicLong(1_000);
        var limit = Duration.ofSeconds(60);
        var health =
                new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), nanos::get, HOUR, limit, logged::add);
        events.start();
        health.down("connection reset");
        // past the limit with nobody reading: found only at the close
        nanos.addAndGet(limit.plusSeconds(1).toNanos());
        health.closed();
        assertThat(read(health).components())
                .as("as it was at the close")
                .containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        heard.nothingMore("after the close");
        assertThat(logged).as("logged").isEmpty();
    }

    @Test
    void aChannelRefusedThreeTimesInARowForTheConnectionsLimitDegradesTheConnectionUntilItOpens()
            throws InterruptedException {
        events.start();
        // the limit is the health's own, 60 s, which the transport's streaks are measured against
        var limit = health.connectionDownLimit();
        assertThat(limit).isEqualTo(Duration.ofSeconds(60));

        assertThat(readRefusing(streak("a session's channel", 1, Duration.ZERO)).components())
                .as("one refusal")
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        assertThat(readRefusing(streak("a session's channel", 2, limit.multipliedBy(5)))
                        .components())
                .as("two refusals, however long")
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        assertThat(readRefusing(streak("a session's channel", 9, limit.minusMillis(1)))
                        .components())
                .as("many refusals, within the limit")
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        heard.nothingMore("a streak that is not enough");

        var reading = readRefusing(streak("a session's channel", 3, limit));
        assertThat(reading.components()).containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        assertThat(reading.state()).isEqualTo(HealthState.DEGRADED);
        var degraded = heard.next();
        assertThat(degraded.component()).isEqualTo(HealthComponent.CONNECTION);
        assertThat(degraded.previous()).isEqualTo(HealthState.HEALTHY);
        assertThat(degraded.state()).isEqualTo(HealthState.DEGRADED);
        assertThat(degraded.reason())
                .isEqualTo("the broker connection is up, but a session's channel has been refused 3 times in a row"
                        + " over 60 s, the last: java.io.IOException: 404 NOT_FOUND - no exchange");

        readRefusing(streak("a session's channel", 4, limit.plusSeconds(30)));
        heard.nothingMore("still degraded");

        // the next successful open ends the streak
        var opened = read();
        assertThat(opened.components()).containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        var healthy = heard.next();
        assertThat(healthy.previous()).isEqualTo(HealthState.DEGRADED);
        assertThat(healthy.state()).isEqualTo(HealthState.HEALTHY);
        heard.nothingMore("told once each way");
        assertThat(logged).extracting(HealthEvent::state).containsExactly(HealthState.DEGRADED, HealthState.HEALTHY);
    }

    @Test
    void ofSeveralChannelsRefusedTheOneThatQualifiesIsNamedAndItsStreakIsNotMixedWithAnothers()
            throws InterruptedException {
        events.start();
        var limit = health.connectionDownLimit();
        // many in a row on one, long on the other: neither is both
        var neither = readRefusing(
                streak("a session's channel", 7, limit.dividedBy(2)),
                streak("the alive channel", 2, limit.multipliedBy(3)));
        assertThat(neither.components()).containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        heard.nothingMore("no channel is both");

        readRefusing(
                streak("a session's channel", 3, limit.plusSeconds(1)),
                streak("the alive channel", 5, limit.plusSeconds(20)));
        assertThat(heard.next().reason()).contains("the alive channel has been refused 5 times in a row over 80 s");
    }

    @Test
    void aConnectionDownIsTheDownLimitsToTellNotTheChannelsAndAClosedFeedTellsNothingMore()
            throws InterruptedException {
        var nanos = new AtomicLong(1_000);
        var limit = Duration.ofSeconds(60);
        var health =
                new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), nanos::get, HOUR, limit, logged::add);
        events.start();
        health.down("connection reset");
        assertThat(readRefusing(health, streak("a session's channel", 5, limit)).components())
                .as("down within its limit, and a streak the transport no longer reports as one")
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        health.up();

        readRefusing(health, streak("a session's channel", 3, limit));
        var degraded = heard.next();
        assertThat(degraded.state()).isEqualTo(HealthState.DEGRADED);
        // closing ends the retries: the part is kept as it is found without them, and nothing is told
        health.closed();
        assertThat(readRefusing(health, streak("a session's channel", 8, limit.multipliedBy(2)))
                        .components())
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        heard.nothingMore("after the close");
        assertThat(logged).containsExactly(degraded);
    }

    @Test
    void aConnectionDegradedAtTheCloseStaysSoAndNothingMoreIsTold() throws InterruptedException {
        var nanos = new AtomicLong(1_000);
        var limit = Duration.ofSeconds(60);
        var health =
                new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), nanos::get, HOUR, limit, logged::add);
        events.start();
        health.down("connection reset");
        nanos.addAndGet(limit.plusSeconds(1).toNanos());
        assertThat(read(health).components()).containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        var degraded = heard.next();
        assertThat(degraded.reason()).isEqualTo("the broker connection has been down for 61 s, over its limit of 60 s");
        health.closed();
        nanos.addAndGet(limit.toNanos());
        var after = read(health);
        assertThat(after.components()).containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        health.up();
        assertThat(read(health).components())
                .as("an up told after the close")
                .containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        heard.nothingMore("after the close");
        assertThat(logged).as("logged").containsExactly(degraded);
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anUpToldWhileTheCloseFindsTheConnectionIsKeptOrToldNothing() throws Exception {
        var time = new AtomicLong(1_000);
        var limit = Duration.ofSeconds(60);
        var closing = new AtomicReference<@Nullable Thread>();
        var finding = new CountDownLatch(1);
        var letItGo = new CountDownLatch(1);
        // the close's thread holds on in its finding, the connection read degraded and not yet kept
        LongSupplier nanos = () -> {
            if (Thread.currentThread().equals(closing.get())) {
                finding.countDown();
                await(letItGo);
            }
            return time.get();
        };
        var health = new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), nanos, HOUR, limit, logged::add);
        events.start();
        health.down("connection reset");
        time.addAndGet(limit.plusSeconds(1).toNanos());
        assertThat(read(health).components()).containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        var degraded = heard.next();

        var closer = Worker.unstarted(health::closed);
        closing.set(closer.thread());
        closer.thread().start();
        assertThat(finding.await(10, TimeUnit.SECONDS))
                .as("the close finding the connection")
                .isTrue();
        var upper = Worker.start(health::up);
        awaitParkedOrEnded(upper.thread());
        assertThat(upper.thread().getState()).as("the up waiting for the close").isEqualTo(Thread.State.WAITING);
        assertThat(health.connectionDown())
                .as("the connection, as the up waits")
                .isTrue();
        letItGo.countDown();
        closer.ended();
        upper.ended();

        assertThat(read(health).components())
                .as("as last told")
                .containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        heard.nothingMore("an up the close came before");
        assertThat(logged).as("logged").containsExactly(degraded);
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aConnectionChangeToldBeforeTheCloseIsLoggedBeforeItReturns() throws Exception {
        var nanos = new AtomicLong(1_000);
        var limit = Duration.ofSeconds(60);
        var order = new CopyOnWriteArrayList<String>();
        var logging = new CountDownLatch(1);
        var letItGo = new CountDownLatch(1);
        // the reading's thread holds on in the log, the change told
        HealthMonitor.Log log = change -> {
            logging.countDown();
            await(letItGo);
            order.add("logged " + change.component() + " " + change.state());
        };
        var health = new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), nanos::get, HOUR, limit, log);
        events.start();
        health.down("connection reset");
        nanos.addAndGet(limit.plusSeconds(1).toNanos());

        var reader = Worker.start(() -> read(health));
        assertThat(logging.await(10, TimeUnit.SECONDS))
                .as("the reading logging")
                .isTrue();
        var closer = Worker.start(() -> {
            health.closed();
            order.add("closed");
        });
        awaitParkedOrEnded(closer.thread());
        letItGo.countDown();
        reader.ended();
        closer.ended();

        assertThat(order).containsExactly("logged CONNECTION DEGRADED", "closed");
        assertThat(heard.next().state()).isEqualTo(HealthState.DEGRADED);
        heard.nothingMore("after the close");
    }

    @Test
    void aConnectionDownForLongerThanItsLimitIsDegradedAndToldAtOnceWhenItIsUp() throws InterruptedException {
        var nanos = new AtomicLong(1_000);
        var limit = Duration.ofSeconds(60);
        var health =
                new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), nanos::get, HOUR, limit, logged::add);
        events.start();
        assertThat(read(health).components()).containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);

        health.down("connection reset");
        nanos.addAndGet(limit.toNanos());
        assertThat(read(health).components())
                .as("down for its limit")
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        health.down("connection refused, a reconnect failing");
        nanos.incrementAndGet();
        var reading = read(health);
        assertThat(reading.components())
                .as("down for longer, from the first down")
                .containsEntry(HealthComponent.CONNECTION, HealthState.DEGRADED);
        assertThat(reading.state()).isEqualTo(HealthState.DEGRADED);
        var degraded = heard.next();
        assertThat(degraded.component()).isEqualTo(HealthComponent.CONNECTION);
        assertThat(degraded.state()).isEqualTo(HealthState.DEGRADED);
        assertThat(degraded.reason())
                .isEqualTo("the broker connection has been down for 60.001 s, over its limit of 60 s");
        read(health);
        heard.nothingMore("still down");

        health.up();
        var up = heard.next();
        assertThat(up.previous()).isEqualTo(HealthState.DEGRADED);
        assertThat(up.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(up.reason()).isEqualTo("the broker connection is up");
        health.down("connection reset");
        nanos.addAndGet(limit.toNanos());
        assertThat(read(health).components())
                .as("down again, counted from the new loss")
                .containsEntry(HealthComponent.CONNECTION, HealthState.HEALTHY);
        heard.nothingMore("up, then down within the limit");
        assertThat(logged).extracting(HealthEvent::state).containsExactly(HealthState.DEGRADED, HealthState.HEALTHY);
        assertThatThrownBy(() -> health.watched(HealthComponent.CONNECTION, 0, HealthState.STALLED, "watched"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLimitsOfAHealthBuiltWithoutTheConfigurationAreTheConfigurationsDefaults() {
        var defaults = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectProduction()
                .setAccessToken("token")
                .build();
        assertThat(HealthMonitor.CATALOG_STALE_LIMIT).isEqualTo(defaults.getCatalogStaleLimit());
        assertThat(HealthMonitor.CONNECTION_DOWN_LIMIT).isEqualTo(defaults.getConnectionDownLimit());
    }

    @Test
    void aReadingOlderThanOneToldAlreadyTellsNothing() throws InterruptedException {
        events.start();
        long first = health.nextReading();
        long second = health.nextReading();
        health.assess(
                second,
                true,
                false,
                false,
                transport(),
                List.of(),
                alives(),
                List.of(),
                recovery(),
                stale(),
                caches(),
                noEvents());
        assertThat(heard.next().state()).isEqualTo(HealthState.DEGRADED);

        // read before the one above, told after it: what it found is older
        var older = health.assess(
                first,
                true,
                false,
                false,
                transport(),
                List.of(),
                alives(),
                List.of(),
                recovery(),
                List.of(),
                caches(),
                noEvents());
        assertThat(older.components()).as("what it read").containsEntry(HealthComponent.CATALOGS, HealthState.HEALTHY);
        heard.nothingMore("an older reading's change");

        health.assess(
                health.nextReading(),
                true,
                false,
                false,
                transport(),
                List.of(),
                alives(),
                List.of(),
                recovery(),
                List.of(),
                caches(),
                noEvents());
        assertThat(heard.next().state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    void aNewerReadingOfOnePartHoldsBackNoChangeAnOlderReadingFoundOfTheOthers() throws InterruptedException {
        events.start();
        long whole = health.nextReading();
        // read after the whole feed's reading, told before it: of its own part only
        health.lagging(1, true);
        assertThat(heard.next().component()).isEqualTo(HealthComponent.SESSION);

        health.assess(
                whole,
                true,
                true,
                true,
                transport(),
                List.of(),
                alives(),
                List.of(session(1, true)),
                recovery(),
                stale(),
                caches(),
                noEvents());
        var catalogs = heard.next();
        assertThat(catalogs.component())
                .as("a part the newer reading did not read")
                .isEqualTo(HealthComponent.CATALOGS);
        assertThat(catalogs.state()).isEqualTo(HealthState.DEGRADED);
        heard.nothingMore("the session, which the newer reading told");

        health.lagging(1, false);
        assertThat(heard.next().state()).isEqualTo(HealthState.HEALTHY);
        health.assess(
                whole,
                true,
                true,
                true,
                transport(),
                List.of(),
                alives(),
                List.of(session(1, false)),
                recovery(),
                stale(),
                caches(),
                noEvents());
        heard.nothingMore("an older reading of a part a newer one told");
    }

    @Test
    void theStateIsTheWorstOfThePartsTheFeedHasNow() {
        var notStarted = health.assess(
                health.nextReading(),
                false,
                false,
                false,
                transport(),
                List.of(),
                alives(),
                List.of(),
                recovery(),
                List.of(),
                caches(),
                noEvents());
        assertThat(notStarted.components()).isEmpty();
        assertThat(notStarted.state()).isEqualTo(HealthState.HEALTHY);

        var started = health.assess(
                health.nextReading(),
                true,
                false,
                false,
                transport(),
                List.of(),
                alives(),
                List.of(),
                recovery(),
                List.of(),
                caches(),
                noEvents());
        assertThat(started.components())
                .containsOnlyKeys(
                        HealthComponent.EVENTS,
                        HealthComponent.TIMERS,
                        HealthComponent.THREADS,
                        HealthComponent.CATALOGS,
                        HealthComponent.API);

        var replay = health.assess(
                health.nextReading(),
                true,
                true,
                false,
                transport(),
                List.of(),
                alives(),
                List.of(session(1, false)),
                recovery(),
                List.of(),
                caches(),
                noEvents());
        assertThat(replay.components())
                .containsOnlyKeys(
                        HealthComponent.EVENTS,
                        HealthComponent.CATALOGS,
                        HealthComponent.TIMERS,
                        HealthComponent.THREADS,
                        HealthComponent.CONSUMER,
                        HealthComponent.SESSION,
                        HealthComponent.CONNECTION,
                        HealthComponent.API);

        health.watched(HealthComponent.RECOVERY, 0, HealthState.STALLED, "its turn has not ended");
        var live = health.assess(
                health.nextReading(),
                true,
                true,
                true,
                transport(),
                List.of(),
                alives(),
                List.of(session(1, false)),
                recovery(),
                stale(),
                caches(),
                noEvents());
        assertThat(live.components())
                .containsExactly(
                        Map.entry(HealthComponent.CONSUMER, HealthState.HEALTHY),
                        Map.entry(HealthComponent.SESSION, HealthState.HEALTHY),
                        Map.entry(HealthComponent.ALIVES, HealthState.HEALTHY),
                        Map.entry(HealthComponent.RECOVERY, HealthState.STALLED),
                        Map.entry(HealthComponent.EVENTS, HealthState.HEALTHY),
                        Map.entry(HealthComponent.TIMERS, HealthState.HEALTHY),
                        Map.entry(HealthComponent.THREADS, HealthState.HEALTHY),
                        Map.entry(HealthComponent.CATALOGS, HealthState.DEGRADED),
                        Map.entry(HealthComponent.CONNECTION, HealthState.HEALTHY),
                        Map.entry(HealthComponent.API, HealthState.HEALTHY));
        assertThat(live.state()).as("the worst").isEqualTo(HealthState.STALLED);
    }

    @Test
    void whatTheWatchFindsIsToldAndTheWorseOfItAndTheLaggingCounts() throws InterruptedException {
        events.start();
        health.watched(HealthComponent.SESSION, 1, HealthState.STALLED, "its callback has run for 31 s");
        var stalled = heard.next();
        assertThat(stalled.session()).isSameAs(first);
        assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
        assertThat(stalled.reason()).isEqualTo("its callback has run for 31 s");

        health.lagging(1, true);
        heard.nothingMore("lagging is better than stalled");
        assertThat(read(session(1, true)).sessions().getFirst().state()).isEqualTo(HealthState.STALLED);

        health.watched(HealthComponent.SESSION, 1, HealthState.HEALTHY, "its callback returned");
        var lagging = heard.next();
        assertThat(lagging.previous()).isEqualTo(HealthState.STALLED);
        assertThat(lagging.state()).as("still lagging").isEqualTo(HealthState.DEGRADED);
        assertThat(lagging.reason()).contains("is lagging");

        health.lagging(1, false);
        assertThat(heard.next().state()).isEqualTo(HealthState.HEALTHY);

        health.watched(HealthComponent.TIMERS, 0, HealthState.STALLED, "no tick for 30 s");
        assertThat(heard.next().component()).isEqualTo(HealthComponent.TIMERS);
        assertThat(read().components())
                .as("a part only the watch knows")
                .containsEntry(HealthComponent.TIMERS, HealthState.STALLED);
        assertThatThrownBy(() -> health.watched(HealthComponent.CATALOGS, 0, HealthState.STALLED, "no"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofTwoNotesOfAPartTheLaterIsKeptAndTheEarlierToldAfterItTellsNothing() throws InterruptedException {
        events.start();
        var stalled = health.note(HealthComponent.EVENTS, 0, HealthState.STALLED, "in one callback for 31 s");
        var moving = health.note(HealthComponent.EVENTS, 0, HealthState.HEALTHY, "moves again");
        moving.tell();
        heard.nothingMore("healthy, as it was");
        stalled.tell();
        heard.nothingMore("the earlier note, told after the later");
        assertThat(read().components()).containsEntry(HealthComponent.EVENTS, HealthState.HEALTHY);

        health.note(HealthComponent.EVENTS, 0, HealthState.STALLED, "in one callback for 31 s")
                .tell();
        var told = heard.next();
        assertThat(told.state()).isEqualTo(HealthState.STALLED);
        assertThat(told.reason()).isEqualTo("in one callback for 31 s");
        assertThat(logged).as("each change logged once, as it is told").containsExactly(told);
    }

    @Test
    void nothingIsToldOnceTheEventsDispatcherStopped() throws InterruptedException {
        events.start();
        events.stop();
        health.lagging(1, true);
        heard.nothingMore("after the feed closed");
        assertThat(read(session(1, true)).sessions().getFirst().state())
                .as("still read")
                .isEqualTo(HealthState.DEGRADED);
    }

    @Test
    void theFeedsPartsAreReadFromWhatItsStartAndItsOpenBuilt() {
        try (var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(configuration, events, client -> {});
            try {
                var notOpen = health.health(core, null);
                assertThat(notOpen.components())
                        .containsOnlyKeys(
                                HealthComponent.EVENTS,
                                HealthComponent.TIMERS,
                                HealthComponent.THREADS,
                                HealthComponent.CATALOGS,
                                HealthComponent.API);
                assertThat(notOpen.catalogs())
                        .extracting(FeedHealth.Catalog::name)
                        .containsExactly(
                                "market descriptions", "market variants", "void reasons", "match status descriptions");
                assertThat(notOpen.sessions()).isEmpty();
                assertThat(notOpen.transport()).isEqualTo(new FeedHealth.Transport(false, 0, 0));

                var registry = new SessionRegistry(null);
                var built = registry.builder()
                        .setListener(new Silent())
                        .setMessageInterest(MessageInterest.LIVE_ONLY)
                        .build();
                var second = registry.builder()
                        .setListener(new Silent())
                        .setMessageInterest(MessageInterest.PREMATCH_ONLY)
                        .build();
                var plan = Sessions.plan(registry.open(), core.producers().getAvailableProducers(), null);
                var live = OpenFeed.build(core, plan, configuration, health);
                try {
                    health.lagging(2, true);
                    var open = health.health(core, live);
                    assertThat(open.components())
                            .containsOnlyKeys(
                                    HealthComponent.CONSUMER,
                                    HealthComponent.SESSION,
                                    HealthComponent.ALIVES,
                                    HealthComponent.RECOVERY,
                                    HealthComponent.EVENTS,
                                    HealthComponent.TIMERS,
                                    HealthComponent.THREADS,
                                    HealthComponent.CATALOGS,
                                    HealthComponent.CONNECTION,
                                    HealthComponent.API);
                    assertThat(open.sessions())
                            .extracting(FeedHealth.Session::id)
                            .containsExactly(1, 2);
                    assertThat(open.sessions().getFirst().session()).isSameAs(built);
                    assertThat(open.sessions().getLast().session()).isSameAs(second);
                    assertThat(open.sessions())
                            .extracting(FeedHealth.Session::lagging)
                            .containsExactly(false, true);
                    assertThat(open.recovery())
                            .isEqualTo(requireNonNull(live.actor()).counters().snapshot());
                } finally {
                    live.close();
                }
            } finally {
                core.close();
            }
        }
    }

    @Test
    void aReplayFeedHasNoAlivesNorRecovery() {
        try (var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(configuration, events, client -> {});
            try {
                var registry = new SessionRegistry(null);
                registry.builder().setListener(new Silent()).buildReplay();
                var plan = Sessions.plan(registry.open(), core.producers().getAvailableProducers(), null);
                var replay = OpenFeed.build(core, plan, configuration, health);
                try {
                    assertThat(health.health(core, replay).components())
                            .containsOnlyKeys(
                                    HealthComponent.CONSUMER,
                                    HealthComponent.SESSION,
                                    HealthComponent.EVENTS,
                                    HealthComponent.TIMERS,
                                    HealthComponent.THREADS,
                                    HealthComponent.CATALOGS,
                                    HealthComponent.CONNECTION,
                                    HealthComponent.API);
                } finally {
                    replay.close();
                }
            } finally {
                core.close();
            }
        }
    }

    // ------------------------------------------------------------------ the API's acceptance of the token

    @Test
    void aRefusedTokenStallsTheApiPartWithItsStatusAndTimeAndIsToldOnce() throws InterruptedException {
        events.start();
        assertThat(read().components()).containsEntry(HealthComponent.API, HealthState.HEALTHY);

        health.refused(call(401, null));
        var refused = heard.next();
        assertThat(refused.component()).isEqualTo(HealthComponent.API);
        assertThat(refused.session()).isNull();
        assertThat(refused.previous()).isEqualTo(HealthState.HEALTHY);
        assertThat(refused.state()).isEqualTo(HealthState.STALLED);
        assertThat(refused.reason())
                .isEqualTo("the API refused the access token: GET " + WHOAMI_URI + " answered 401 at " + NOW);
        assertThat(refused.at()).isEqualTo(NOW);
        var reading = read();
        assertThat(reading.components()).containsEntry(HealthComponent.API, HealthState.STALLED);
        assertThat(reading.state()).as("the overall state follows it").isEqualTo(HealthState.STALLED);
        assertThat(reading.counters()).containsEntry("component.api.state", 2L);

        health.refused(call(403, null));
        read();
        heard.nothingMore("a refusal while refused: one change, with the first one's reason");
        assertThat(logged).containsExactly(refused);

        // a 403 stalls it just the same
        var other = new HealthMonitor(events, id -> null, InstantSource.fixed(NOW), HOUR, logged::add);
        other.refused(call(403, null));
        assertThat(heard.next().reason()).contains("answered 403");
        assertThat(read(other).components()).containsEntry(HealthComponent.API, HealthState.STALLED);
    }

    @Test
    void aCallThatSucceedsMakesTheApiPartHealthyAgain() throws InterruptedException {
        events.start();
        health.called(call(200, null));
        heard.nothingMore("a success of a part that was never refused");

        health.refused(call(401, null));
        var refused = heard.next();
        health.called(call(204, null));
        var accepted = heard.next();
        assertThat(accepted.component()).isEqualTo(HealthComponent.API);
        assertThat(accepted.previous()).isEqualTo(HealthState.STALLED);
        assertThat(accepted.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(accepted.reason())
                .isEqualTo("the API answered GET " + WHOAMI_URI + " with 204 at " + NOW
                        + ", so it takes the access token again");
        var reading = read();
        assertThat(reading.components()).containsEntry(HealthComponent.API, HealthState.HEALTHY);
        assertThat(reading.state()).isEqualTo(HealthState.HEALTHY);
        health.called(call(200, null));
        heard.nothingMore("a success while healthy");
        assertThat(logged).containsExactly(refused, accepted);

        health.refused(call(403, null));
        assertThat(heard.next().state()).as("refused again").isEqualTo(HealthState.STALLED);
    }

    @Test
    void whatIsNoRefusalLeavesTheApiPartAsItWas() throws InterruptedException {
        events.start();
        var failures = new ApiCall[] {
            call(500, new IOException("answered 500")),
            call(503, null),
            call(429, null),
            call(404, null),
            call(0, new HttpTimeoutException("no complete answer before the deadline")),
            call(0, new IOException("connection reset")),
            // an answer that began as a success and broke off is no success of the token
            call(200, new IOException("cut off")),
        };
        for (var failure : failures) {
            health.called(failure);
        }
        assertThat(read().components()).containsEntry(HealthComponent.API, HealthState.HEALTHY);
        heard.nothingMore("failures of a part never refused");

        health.refused(call(401, null));
        heard.next();
        for (var failure : failures) {
            health.called(failure);
        }
        // the attempt a refusal comes with is told to called() as well, and is no success
        health.called(call(401, new IOException("answered 401")));
        health.called(call(403, null));
        assertThat(read().components()).containsEntry(HealthComponent.API, HealthState.STALLED);
        heard.nothingMore("nothing but a success heals it");
    }

    /** The REST transport's own words reach the part: a refusal, other failures, a success. */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theApiPartFollowsWhatTheRestTransportMeets() throws InterruptedException {
        try (var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .setHttpClientTimeout(Duration.ofMillis(800))
                    .build();
            events.start();
            try (var client = new ApiClient(configuration, new ApiTee(List.of(events, health)))) {
                api.respond(WHOAMI, 500, "");
                assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
                api.respond(WHOAMI, Reply.of(200, "").after(Duration.ofSeconds(3)));
                assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
                api.respond(WHOAMI, 429, "");
                assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
                assertThat(read().components())
                        .as("a 5xx, a timeout and a 429 are no refusal")
                        .containsEntry(HealthComponent.API, HealthState.HEALTHY);
                heard.nothingMore("failures other than a refusal");

                api.respond(WHOAMI, 401, "");
                assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
                var refused = heard.next();
                assertThat(refused.component()).isEqualTo(HealthComponent.API);
                assertThat(refused.state()).isEqualTo(HealthState.STALLED);
                assertThat(refused.reason()).contains("answered 401").contains(WHOAMI);
                assertThat(read().components()).containsEntry(HealthComponent.API, HealthState.STALLED);

                api.respond(WHOAMI, 403, "");
                assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
                api.respond(WHOAMI, 500, "");
                assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
                assertThat(read().components())
                        .as("a failure other than a refusal does not heal it")
                        .containsEntry(HealthComponent.API, HealthState.STALLED);
                heard.nothingMore("a second refusal and a 5xx");

                api.respond(WHOAMI, 200, Fixtures.read("rest/whoami/bookmaker_details.xml"));
                client.fetchWhoAmI();
                var accepted = heard.next();
                assertThat(accepted.previous()).isEqualTo(HealthState.STALLED);
                assertThat(accepted.state()).isEqualTo(HealthState.HEALTHY);
                assertThat(read().components()).containsEntry(HealthComponent.API, HealthState.HEALTHY);
            }
        }
    }

    // ------------------------------------------------------------------ threads

    /** Waits for the thread to park, as on a lock it waits for, or to end. */
    private static void awaitParkedOrEnded(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TERMINATED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).as("parked or ended").isIn(Thread.State.WAITING, Thread.State.TERMINATED);
    }

    /** A thread of the test's, what it throws failing the test. */
    private record Worker(Thread thread, FutureTask<@Nullable Void> body) {

        static Worker unstarted(Runnable body) {
            var task = new FutureTask<@Nullable Void>(body, null);
            return new Worker(Thread.ofPlatform().unstarted(task), task);
        }

        static Worker start(Runnable body) {
            var worker = unstarted(body);
            worker.thread().start();
            return worker;
        }

        /** Waits for it to end, within 10 s: what it threw, or its not ending, fails the test. */
        void ended() throws Exception {
            body.get(10, TimeUnit.SECONDS);
            thread.join(10_000);
            assertThat(thread.isAlive()).as("%s ended", thread).isFalse();
        }
    }

    /** Waits for the test to let it go, in a lambda: the test fails if it does not within 10 s. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("not let go within 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final String WHOAMI = "/v1/users/whoami";

    private static final URI WHOAMI_URI = URI.create("https://api.invalid" + WHOAMI);

    private static ApiCall call(int status, @Nullable Exception failure) {
        return new ApiCall("GET", WHOAMI_URI, status, Duration.ZERO, 1, failure);
    }

    // ------------------------------------------------------------------ readings

    private FeedHealth read(FeedHealth.Session... sessions) {
        return read(health, sessions);
    }

    /** As the feed reads one once open. */
    private FeedHealth read(HealthMonitor health, FeedHealth.Session... sessions) {
        return health.assess(
                health.nextReading(),
                true,
                true,
                true,
                transport(),
                List.of(),
                alives(),
                List.of(sessions),
                recovery(),
                List.of(),
                caches(),
                noEvents());
    }

    private static ChannelRefusals.Streak streak(String channel, int count, Duration lasted) {
        return new ChannelRefusals.Streak(channel, count, lasted, "java.io.IOException: 404 NOT_FOUND - no exchange");
    }

    /** As the feed reads one once open, with these channels refused. */
    private FeedHealth readRefusing(ChannelRefusals.Streak... refusing) {
        return readRefusing(health, refusing);
    }

    private FeedHealth readRefusing(HealthMonitor health, ChannelRefusals.Streak... refusing) {
        return health.assess(
                health.nextReading(),
                true,
                true,
                true,
                transport(),
                List.of(refusing),
                alives(),
                List.of(),
                recovery(),
                List.of(),
                caches(),
                noEvents());
    }

    private FeedHealth read(List<CatalogHealth> catalogs) {
        return health.assess(
                health.nextReading(),
                true,
                false,
                false,
                transport(),
                List.of(),
                alives(),
                List.of(),
                recovery(),
                catalogs,
                caches(),
                noEvents());
    }

    /** As the feed reads one: lagging as the monitor has it. */
    private FeedHealth.Session session(int id, boolean lagging) {
        assertThat(health.lagging(id)).as("session %s lagging", id).isEqualTo(lagging);
        return new FeedHealth.Session(
                id,
                id == 1 ? first : new OddsFeedSession() {},
                HealthState.HEALTHY,
                lagging,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private static List<CatalogHealth> stale() {
        return List.of(catalog("void reasons", HOUR.toSeconds()));
    }

    private static CatalogHealth catalog(String name, Duration staleFor) {
        return new CatalogHealth(name, 1, staleFor, 0, 0, 0);
    }

    private static CatalogHealth catalog(String name, long staleSeconds) {
        return catalog(name, Duration.ofSeconds(staleSeconds));
    }

    private static FeedHealth.Transport transport() {
        return new FeedHealth.Transport(true, 0, 0);
    }

    private static FeedHealth.Alives alives() {
        return new FeedHealth.Alives(0, 0, 0, 0);
    }

    private static FeedHealth.Recovery recovery() {
        return new FeedHealth.Recovery(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static FeedHealth.Caches caches() {
        return new FeedHealth.Caches(0, 0, 0, 0, 0, 0);
    }

    private static FeedHealth.Events noEvents() {
        return new FeedHealth.Events(0, 0, 0, 0);
    }

    /** A session listener that does nothing; for the tests of this package. */
    static final class Silent implements OddsFeedListener {
        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {}

        @Override
        public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {}

        @Override
        public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {}

        @Override
        public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {}

        @Override
        public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {}

        @Override
        public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {}

        @Override
        public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {}
    }

    /** Hears the health events, and nothing else. */
    private static final class Heard implements GlobalEventsListener {
        private final BlockingQueue<HealthEvent> events = new LinkedBlockingQueue<>();

        HealthEvent next() throws InterruptedException {
            return requireNonNull(events.poll(10, TimeUnit.SECONDS), "a health event within 10 s");
        }

        void nothingMore(String why) throws InterruptedException {
            @Nullable HealthEvent more = events.poll(200, TimeUnit.MILLISECONDS);
            assertThat(more).as(why).isNull();
        }

        @Override
        public void onHealthEvent(HealthEvent event) {
            events.add(event);
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
