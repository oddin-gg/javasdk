package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.catalog.CatalogHealth;
import com.oddin.oddsfeedsdk.internal.dispatch.AliveDispatcher;
import com.oddin.oddsfeedsdk.internal.dispatch.SessionDispatcher;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryActor;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryCounters;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryEvents;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The feed's health: what {@code getHealth()} reads, and where a part's change of health is told
 * from, to the log and to {@code onHealthEvent}. One per {@code OddsFeed}, for its whole life.
 *
 * <p>A part's state comes from what it is now - a session lagging, as the recovery actor last told,
 * a catalog serving a value stale for its limit or more, or the broker connection down for longer
 * than its limit, as the transport told - and from what the SDK's own watch found of it, through
 * {@link #watched}; the worse of the two. A session's lagging is told as the actor tells it, and the
 * connection coming up as the transport tells it; a catalog's staleness and the connection's time
 * down are found when the health is read. Each reading takes a number before it reads, and what a
 * reading found of a part tells nothing once a newer reading has told of that part: two readings
 * that race cannot tell an older state after a newer one, and a reading of one part - a lagging, a
 * watch, the connection coming up - holds back no change an older reading of the whole feed found
 * of the others.
 *
 * <p>Cheap and safe for concurrent use: a reading reads counters and flags, never waits for a thread
 * of the feed, and holds a lock only to compare what it found with what was told, and to tell and
 * log a change: the events dispatcher only queues it, and no callback of the client's runs there.
 */
public final class HealthMonitor implements RecoveryEvents, ConnectionEvents {

    private static final Logger LOG = LoggerFactory.getLogger(HealthMonitor.class);

    /**
     * How long a catalog serves a value stale, its refreshes failing, before it counts as degraded:
     * the configuration's default, for a health built without the configuration.
     */
    public static final Duration CATALOG_STALE_LIMIT = Duration.ofHours(1);

    /**
     * How long the broker connection may be down before it counts as degraded: the configuration's
     * default, for a health built without the configuration.
     */
    public static final Duration CONNECTION_DOWN_LIMIT = Duration.ofSeconds(60);

    private static final Part CONNECTION = new Part(HealthComponent.CONNECTION, 0);
    /** What {@link #connectionDownSince} holds while the connection is up. */
    private static final long CONNECTION_UP = Long.MIN_VALUE;

    private final EventsDispatcher events;
    private final IntFunction<@Nullable OddsFeedSession> sessions;
    private final InstantSource clock;
    private final Duration catalogStaleLimit;
    private final Duration connectionDownLimit;
    private final LongSupplier nanos;
    private final Log log;

    /**
     * When the broker connection went down, by {@link #nanos}, as the transport told; {@link
     * #CONNECTION_UP} while it is up, and before the feed opens.
     */
    private final AtomicLong connectionDownSince = new AtomicLong(CONNECTION_UP);
    /**
     * The connection's part as found when the feed closed, read from then on and never told; null
     * while the feed is not closed.
     */
    private volatile @Nullable Found connectionAtClose;

    /** The sessions lagging, by the feed's number, as the recovery actor last told. */
    private final Set<Integer> lagging = ConcurrentHashMap.newKeySet();
    /** What the SDK's own watch last found of each part it watches; none until it finds something. */
    private final Map<Part, Found> watched = new ConcurrentHashMap<>();
    /** Taken by every reading before it reads. */
    private final AtomicLong readings = new AtomicLong();
    /**
     * Held to keep what the watch found and take its reading's number in one step, so of two watches
     * of a part the one with the newer number is the one the map holds.
     */
    private final ReentrantLock noting = new ReentrantLock();

    /**
     * Held to compare what a reading found with what was told, and to tell and log the change; and
     * by an up and the close, each in one step with what it changes of the connection's part.
     */
    private final ReentrantLock telling = new ReentrantLock();
    /** The state last told of each part; a part not in it was healthy. Guarded by {@link #telling}. */
    private final Map<Part, HealthState> told = new HashMap<>();
    /** The newest reading told of each part; guarded by {@link #telling}. */
    private final Map<Part, Long> toldReading = new HashMap<>();

    /**
     * @param events where a change is told; the feed's, which drops it once stopped
     * @param sessions what a session's change names its session from, by the feed's number for it
     */
    public HealthMonitor(EventsDispatcher events, IntFunction<@Nullable OddsFeedSession> sessions) {
        this(events, sessions, Log.SLF4J);
    }

    /**
     * @param log where each change is logged; a test's, to read the lines
     */
    public HealthMonitor(EventsDispatcher events, IntFunction<@Nullable OddsFeedSession> sessions, Log log) {
        this(
                events,
                sessions,
                InstantSource.system(),
                System::nanoTime,
                CATALOG_STALE_LIMIT,
                CONNECTION_DOWN_LIMIT,
                log);
    }

    /** With the clock and the catalogs' limit a test sets. */
    HealthMonitor(
            EventsDispatcher events,
            IntFunction<@Nullable OddsFeedSession> sessions,
            InstantSource clock,
            Duration catalogStaleLimit) {
        this(events, sessions, clock, catalogStaleLimit, Log.SLF4J);
    }

    HealthMonitor(
            EventsDispatcher events,
            IntFunction<@Nullable OddsFeedSession> sessions,
            InstantSource clock,
            Duration catalogStaleLimit,
            Log log) {
        this(events, sessions, clock, System::nanoTime, catalogStaleLimit, CONNECTION_DOWN_LIMIT, log);
    }

    /**
     * @param nanos the monotonic clock the connection's time down is measured by; a test's
     * @param catalogStaleLimit how long a catalog serves a value stale before it is degraded
     * @param connectionDownLimit how long the broker connection may be down before it is degraded
     */
    public HealthMonitor(
            EventsDispatcher events,
            IntFunction<@Nullable OddsFeedSession> sessions,
            InstantSource clock,
            LongSupplier nanos,
            Duration catalogStaleLimit,
            Duration connectionDownLimit,
            Log log) {
        this.events = events;
        this.sessions = sessions;
        this.clock = clock;
        this.nanos = nanos;
        this.catalogStaleLimit = catalogStaleLimit;
        this.connectionDownLimit = connectionDownLimit;
        this.log = log;
    }

    // ------------------------------------------------------------------ what changes a part's state

    /** The recovery actor's word on a session, on its thread: told at once when it changes the state. */
    @Override
    public void lagging(int session, boolean lagging) {
        if (lagging) {
            this.lagging.add(session);
        } else {
            this.lagging.remove(session);
        }
        long reading = nextReading();
        var part = new Part(HealthComponent.SESSION, session);
        tell(reading, Map.of(part, worse(laggingFound(session), watched.get(part), false)));
    }

    /**
     * The transport's word that the connection is up, on the thread that saw it: told at once when it
     * changes the state, as a connection down for longer than its limit was degraded until now.
     */
    @Override
    public void up() {
        // in one step with the close: the connection's part it keeps is the one last told
        telling.lock();
        try {
            connectionDownSince.set(CONNECTION_UP);
            long reading = nextReading();
            tell(reading, Map.of(CONNECTION, connectionFound()));
        } finally {
            telling.unlock();
        }
    }

    /** The transport's word that the connection was lost: found degraded once down for its limit. */
    @Override
    public void down(String reason) {
        connectionDownSince.compareAndSet(CONNECTION_UP, nanos.getAsLong());
    }

    /**
     * The feed is closing: from now on the connection's part reads as it is found now, its time down
     * no longer counted, and no change of it is told or logged. A closed feed's connection is not
     * one that stays down: closing it tells neither up nor down. Taken in one step with an up the
     * transport tells, so the part kept is the one last told: an up told before it is in it, and one
     * told after it tells nothing. A change of it told before is logged before it returns. Once
     * only; a later call changes nothing.
     */
    public void closed() {
        telling.lock();
        try {
            if (connectionAtClose == null) {
                connectionAtClose = connectionFound();
            }
        } finally {
            telling.unlock();
        }
    }

    /**
     * What the SDK's own watch found of a part: told at once when it changes the part's state. A
     * part it watches is one the feed has from then on.
     *
     * @param session the feed's number for the session, for {@link HealthComponent#SESSION}; ignored
     *     for the other parts
     * @throws IllegalArgumentException for {@link HealthComponent#CATALOGS} or {@link
     *     HealthComponent#CONNECTION}, whose state is their own
     */
    public void watched(HealthComponent component, int session, HealthState state, String reason) {
        note(component, session, state, reason).tell();
    }

    /**
     * What the SDK's own watch found of a part, kept now and told when the result's {@link
     * Noted#tell} runs: a watch that decides under a lock of its own notes there, in the order it
     * decides, and tells - which logs - once out of it. Of two notes of a part, the later is the
     * one kept, and the earlier tells nothing once the later has told.
     *
     * @throws IllegalArgumentException for {@link HealthComponent#CATALOGS} or {@link
     *     HealthComponent#CONNECTION}, whose state is their own
     */
    public Noted note(HealthComponent component, int session, HealthState state, String reason) {
        if (component == HealthComponent.CATALOGS || component == HealthComponent.CONNECTION) {
            throw new IllegalArgumentException("the " + component + " state is its own");
        }
        var part = new Part(component, component == HealthComponent.SESSION ? session : 0);
        var found = new Found(state, reason);
        long reading;
        noting.lock();
        try {
            watched.put(part, found);
            reading = nextReading();
        } finally {
            noting.unlock();
        }
        var derived = component == HealthComponent.SESSION ? laggingFound(session) : null;
        var now = worse(derived, found, true);
        return () -> tell(reading, Map.of(part, now));
    }

    /** What the watch found of a part, kept, to be told. */
    @FunctionalInterface
    public interface Noted {
        /** Tells the change, if the part's state changed and no newer reading of it has told. */
        void tell();
    }

    // ------------------------------------------------------------------ getHealth()

    /**
     * The health of the feed as it is now, and any change it finds told.
     *
     * @param core what the feed's start built; null before it has started
     * @param run what its open built; null before it opens, or after an open that failed
     */
    public FeedHealth health(@Nullable FeedCore core, @Nullable OpenFeed run) {
        long reading = nextReading();
        var sessionHealth = new ArrayList<FeedHealth.Session>();
        @Nullable RecoveryActor actor = null;
        var transport = new FeedHealth.Transport(false, 0);
        var aliveHealth = new FeedHealth.Alives(0, 0, 0, 0);
        if (run != null) {
            run.sessions().forEach(dispatcher -> sessionHealth.add(session(dispatcher)));
            actor = run.actor();
            transport = new FeedHealth.Transport(
                    run.transport().connectionOpen(), run.transport().reconnects());
            AliveDispatcher alives = run.alives();
            if (alives != null) {
                aliveHealth =
                        new FeedHealth.Alives(alives.queued(), alives.handled(), alives.dropped(), alives.unreadable());
            }
        }
        return assess(
                reading,
                core != null,
                run != null,
                actor != null,
                transport,
                aliveHealth,
                sessionHealth,
                (actor == null ? new RecoveryCounters() : actor.counters()).snapshot(),
                core == null ? List.of() : core.catalogs(),
                core == null ? new FeedHealth.Caches(0, 0, 0, 0, 0, 0) : core.caches(),
                new FeedHealth.Events(
                        events.controlDropped(),
                        events.telemetryDropped(),
                        events.rawDataDropped(),
                        events.callbackFailures()));
    }

    /**
     * The health from what a reading read, each part's state found and any change told.
     *
     * @param started whether the feed has started: its events and catalogs are parts from then on
     * @param opened whether it is open: its connection, consumer and sessions are parts from then on
     * @param recovers whether it runs a recovery, as a feed that is no replay feed does once open:
     *     its alives and recovery are parts from then on
     */
    FeedHealth assess(
            long reading,
            boolean started,
            boolean opened,
            boolean recovers,
            FeedHealth.Transport transport,
            FeedHealth.Alives alives,
            List<FeedHealth.Session> sessionHealth,
            FeedHealth.Recovery recovery,
            List<CatalogHealth> catalogs,
            FeedHealth.Caches caches,
            FeedHealth.Events eventHealth) {
        var found = new LinkedHashMap<Part, Found>();
        if (started) {
            found.put(new Part(HealthComponent.EVENTS, 0), watchedOnly(HealthComponent.EVENTS));
            found.put(new Part(HealthComponent.CATALOGS, 0), catalogsFound(catalogs));
            // the watchdog's own, which starts with the feed
            found.put(new Part(HealthComponent.TIMERS, 0), watchedOnly(HealthComponent.TIMERS));
            found.put(new Part(HealthComponent.THREADS, 0), watchedOnly(HealthComponent.THREADS));
        }
        if (opened) {
            found.put(CONNECTION, connectionFound());
            found.put(new Part(HealthComponent.CONSUMER, 0), watchedOnly(HealthComponent.CONSUMER));
        }
        if (recovers) {
            found.put(new Part(HealthComponent.ALIVES, 0), watchedOnly(HealthComponent.ALIVES));
            found.put(new Part(HealthComponent.RECOVERY, 0), watchedOnly(HealthComponent.RECOVERY));
        }
        var sessionsFound = new ArrayList<FeedHealth.Session>();
        for (FeedHealth.Session session : sessionHealth) {
            var part = new Part(HealthComponent.SESSION, session.id());
            var sessionFound = worse(laggingFound(session.lagging(), session.id()), watched.get(part), false);
            found.put(part, sessionFound);
            sessionsFound.add(withState(session, sessionFound.state()));
        }
        // a part the watch found before the feed had it is one the feed has once it is watched
        watched.forEach((part, watch) -> found.putIfAbsent(part, watch));

        var components = new EnumMap<HealthComponent, HealthState>(HealthComponent.class);
        if (opened) {
            components.put(HealthComponent.SESSION, HealthState.HEALTHY);
        }
        found.forEach((part, state) -> components.merge(part.component(), state.state(), HealthMonitor::worst));
        var state = components.values().stream().reduce(HealthState.HEALTHY, HealthMonitor::worst);

        var catalogHealth = catalogs.stream()
                .map(catalog -> new FeedHealth.Catalog(
                        catalog.name(),
                        stale(catalog) ? HealthState.DEGRADED : HealthState.HEALTHY,
                        catalog.servedStale(),
                        catalog.staleFor(),
                        catalog.failedFetches(),
                        catalog.failing(),
                        catalog.evictedForRoom()))
                .toList();
        tell(reading, found);
        return new FeedHealth(
                state,
                components,
                transport,
                alives,
                sessionsFound,
                recovery,
                catalogHealth,
                caches,
                eventHealth,
                clock.instant());
    }

    /** How long a catalog serves a value stale before it is degraded; for a test. */
    public Duration catalogStaleLimit() {
        return catalogStaleLimit;
    }

    /** How long the broker connection may be down before it is degraded; for a test. */
    public Duration connectionDownLimit() {
        return connectionDownLimit;
    }

    /** Whether the transport has told the connection lost, and not up since; for a test. */
    public boolean connectionDown() {
        return connectionDownSince.get() != CONNECTION_UP;
    }

    /** A new reading's number, after every one taken before: taken before the reading reads. */
    long nextReading() {
        return readings.incrementAndGet();
    }

    /** Whether the session is lagging, as the recovery actor last told; for a reading. */
    boolean lagging(int session) {
        return lagging.contains(session);
    }

    // ------------------------------------------------------------------ what a reading finds

    private FeedHealth.Session session(SessionDispatcher dispatcher) {
        var transport = dispatcher.transport();
        var queue = transport.queue();
        int id = dispatcher.id();
        return new FeedHealth.Session(
                id,
                dispatcher.session(),
                // found by assess, with the watch's word
                HealthState.HEALTHY,
                lagging(id),
                queue.size(),
                queue.overflowed(),
                queue.epochDiscards(),
                transport.skippedAcks(),
                dispatcher.handled(),
                dispatcher.unparsable(),
                dispatcher.oversized(),
                dispatcher.sdkFailures(),
                dispatcher.callbackFailures(),
                dispatcher.unknownProducers(),
                dispatcher.repeatedFixtureChanges());
    }

    private Found laggingFound(int session) {
        return laggingFound(lagging(session), session);
    }

    private static Found laggingFound(boolean lagging, int session) {
        return lagging
                ? new Found(
                        HealthState.DEGRADED,
                        "session " + session + " is lagging: it fell behind with the safety net's resets spent")
                : new Found(HealthState.HEALTHY, "session " + session + " is keeping up");
    }

    private Found connectionFound() {
        Found atClose = connectionAtClose;
        if (atClose != null) {
            return atClose;
        }
        long since = connectionDownSince.get();
        if (since == CONNECTION_UP) {
            return new Found(HealthState.HEALTHY, "the broker connection is up");
        }
        var down = Duration.ofNanos(nanos.getAsLong() - since);
        return down.compareTo(connectionDownLimit) > 0
                ? new Found(
                        HealthState.DEGRADED,
                        "the broker connection has been down for " + seconds(down, RoundingMode.CEILING)
                                + ", over its limit of " + seconds(connectionDownLimit, RoundingMode.UNNECESSARY))
                : new Found(HealthState.HEALTHY, "the broker connection is down, within its limit so far");
    }

    private Found catalogsFound(List<CatalogHealth> catalogs) {
        @Nullable CatalogHealth stalest = null;
        for (CatalogHealth catalog : catalogs) {
            if (stale(catalog) && (stalest == null || catalog.staleFor().compareTo(stalest.staleFor()) > 0)) {
                stalest = catalog;
            }
        }
        if (stalest == null) {
            return new Found(
                    HealthState.HEALTHY,
                    "no catalog has served a value stale for " + hours(catalogStaleLimit) + " or more");
        }
        return new Found(
                HealthState.DEGRADED,
                "the " + stalest.name() + " have served a value stale for " + hours(stalest.staleFor())
                        + ", its refreshes failing");
    }

    private boolean stale(CatalogHealth catalog) {
        return catalog.staleFor().compareTo(catalogStaleLimit) >= 0;
    }

    private Found watchedOnly(HealthComponent component) {
        Found watch = watched.get(new Part(component, 0));
        return watch == null ? new Found(HealthState.HEALTHY, "healthy") : watch;
    }

    /** The worse of the two; on a tie, the one the caller says changed. */
    private static Found worse(@Nullable Found derived, @Nullable Found watch, boolean watchChanged) {
        if (derived == null) {
            return watch == null ? new Found(HealthState.HEALTHY, "healthy") : watch;
        }
        if (watch == null) {
            return derived;
        }
        int compared = watch.state().compareTo(derived.state());
        return compared > 0 || (compared == 0 && watchChanged) ? watch : derived;
    }

    private static HealthState worst(HealthState one, HealthState other) {
        return one.compareTo(other) >= 0 ? one : other;
    }

    /**
     * In seconds, with no more decimals than it has: the time down rounded up to the millisecond, so
     * it never reads as its limit; the limit as it is set.
     */
    private static String seconds(Duration duration, RoundingMode rounding) {
        var nanos = BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
        var shown = rounding == RoundingMode.UNNECESSARY ? nanos : nanos.setScale(3, rounding);
        return shown.stripTrailingZeros().toPlainString() + " s";
    }

    private static String hours(Duration duration) {
        return duration.toHours() + " h " + duration.toMinutesPart() + " min";
    }

    private static FeedHealth.Session withState(FeedHealth.Session session, HealthState state) {
        return new FeedHealth.Session(
                session.id(),
                session.session(),
                state,
                session.lagging(),
                session.queueDepth(),
                session.queueOverflows(),
                session.epochDiscards(),
                session.skippedAcks(),
                session.handled(),
                session.unparsable(),
                session.oversized(),
                session.pipelineFailures(),
                session.callbackFailures(),
                session.unknownProducers(),
                session.repeatedFixtureChanges());
    }

    // ------------------------------------------------------------------ telling

    /**
     * Tells each part whose state differs from the one told last, unless a newer reading has told of
     * that part already: what this one read of it may be older than what that one did. A part this
     * reading did not read is not held back by it.
     */
    private void tell(long reading, Map<Part, Found> found) {
        telling.lock();
        try {
            Instant at = clock.instant();
            found.forEach((part, now) -> {
                if (reading < toldReading.getOrDefault(part, 0L)
                        || (part.equals(CONNECTION) && connectionAtClose != null)) {
                    return;
                }
                toldReading.put(part, reading);
                HealthState was = told.getOrDefault(part, HealthState.HEALTHY);
                if (was == now.state()) {
                    return;
                }
                told.put(part, now.state());
                var session = part.component() == HealthComponent.SESSION ? sessions.apply(part.session()) : null;
                var change = new HealthEvent(part.component(), session, was, now.state(), now.reason(), at);
                // under the lock, so the client hears and the log reads the changes in the order they
                // were told, and the close cannot come between telling one and logging it
                events.health(change);
                log.changed(change);
            });
        } finally {
            telling.unlock();
        }
    }

    /** Where each change of a part's state is logged. */
    @FunctionalInterface
    public interface Log {
        /**
         * The SDK's log: a stall as an error, a degradation as a warning, each loud enough to be seen
         * without the health being read; a part getting better as information.
         */
        Log SLF4J = change -> {
            if (change.state().compareTo(change.previous()) <= 0) {
                LOG.info("The feed's {} is {} now: {}", change.component(), change.state(), change.reason());
            } else if (change.state() == HealthState.STALLED) {
                LOG.error("The feed's {} went {}: {}", change.component(), change.state(), change.reason());
            } else {
                LOG.warn("The feed's {} went {}: {}", change.component(), change.state(), change.reason());
            }
        };

        /**
         * One change, once, told after it was given to the events dispatcher; under the health's
         * lock, so it should return at once.
         */
        void changed(HealthEvent change);
    }

    /** A part of the feed: one session, or a part that is no session, numbered 0. */
    private record Part(HealthComponent component, int session) {}

    /** A state found, and why. */
    private record Found(HealthState state, String reason) {}
}
