package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ApiCallEvent;
import com.oddin.oddsfeedsdk.subscribe.CallbackFailure;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import com.oddin.oddsfeedsdk.subscribe.SafetyNetEvent;
import com.oddin.oddsfeedsdk.subscribe.SessionLagChange;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A restart against the SDK's test environment, as a client with live matches sees it: the feed
 * opens, recovers its producers, and its sessions work through what the recovery and the live
 * matches send. {@link #run} measures it as the SDK saw it, through its public API only: the time
 * to the first answers, each endpoint's latency from the API call events, when every producer is up,
 * and how long each session took to catch up.
 *
 * <p>A shared environment: the feed connects with its own queues, sends the recovery requests every
 * client sends at its start and makes the calls its callbacks cause, and nothing else. It publishes
 * nothing and does not replay.
 *
 * <p>Caught up: a session has caught up from the time of the last message that waited longer than
 * the lag limit, between the broker client taking it off the wire and the session's callback
 * starting. Both times are this machine's, so it needs no agreement between clocks.
 */
final class TestEnvironmentRestart {

    static final Locale EN = Locale.ENGLISH;

    /** What the callbacks do with the messages. */
    enum Reads {
        /** Nothing: a client that only forwards the feed. */
        NOTHING,
        /** The match's name and the name of every market of an odds change, variants included. */
        NAMES
    }

    /**
     * @param label what the run is called in the report
     * @param reads what the callbacks read
     * @param eager whether the eager entity preload is on
     * @param sessions the interest of each session
     * @param lagLimit how long a message may wait for its callback and still be on time
     * @param settle how long every session must stay on time, after every producer is up, before the run ends
     * @param max the longest the run goes on
     * @param duration how long a sustained run goes on, whatever the sessions do; zero for a restart,
     *     which ends once every session is on time
     */
    record Settings(
            String label,
            Reads reads,
            boolean eager,
            List<MessageInterest> sessions,
            Duration lagLimit,
            Duration settle,
            Duration max,
            Duration duration) {

        static final String SELECTOR = "benchmarks.testenv";

        /**
         * The settings an environment variable or system property names, each as {@code
         * benchmarks.testenv.reads} or {@code BENCHMARKS_TESTENV_READS}; empty when {@value #SELECTOR}
         * is not set, which is every build but a person's run. Only the test environment can be
         * named: this never points at anything else.
         *
         * @throws IllegalArgumentException for an environment that is not the test environment, or a value that does not parse
         */
        static Optional<Settings> from(Function<String, @Nullable String> lookup) {
            var selected = lookup.apply(SELECTOR);
            if (selected == null || selected.isBlank()) {
                return Optional.empty();
            }
            if (!selected.strip().equalsIgnoreCase("test")) {
                throw new IllegalArgumentException(
                        SELECTOR + " names the test environment, \"test\"; it was \"" + selected + "\"");
            }
            var reads = Reads.valueOf(or(lookup, "reads", "nothing").strip().toUpperCase(Locale.ROOT));
            var sessions = Arrays.stream(or(lookup, "sessions", "ALL").split(","))
                    .map(name -> MessageInterest.valueOf(name.strip().toUpperCase(Locale.ROOT)))
                    .toList();
            return Optional.of(new Settings(
                    or(lookup, "label", reads.name().toLowerCase(Locale.ROOT)),
                    reads,
                    Boolean.parseBoolean(or(lookup, "eager", "true")),
                    sessions,
                    Duration.ofSeconds(Long.parseLong(or(lookup, "lag", "5"))),
                    Duration.ofSeconds(Long.parseLong(or(lookup, "settle", "30"))),
                    Duration.ofMinutes(Long.parseLong(or(lookup, "max", "12"))),
                    Duration.ofMinutes(Long.parseLong(or(lookup, "duration", "0")))));
        }

        boolean sustained() {
            return !duration.isZero();
        }

        private static String or(Function<String, @Nullable String> lookup, String key, String fallback) {
            var value = lookup.apply(SELECTOR + "." + key);
            return value == null || value.isBlank() ? fallback : value;
        }

        /** Properties first, then the environment. */
        static @Nullable String system(String key) {
            var property = System.getProperty(key);
            return property != null
                    ? property
                    : System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
    }

    /** What the run does with the environment it was started in: the decision is made before anything opens. */
    sealed interface Preflight {

        /** Open the feed with these settings. */
        record Open(Settings settings) implements Preflight {}

        /** Nothing was asked for: the test passes by skipping. */
        record Skip(String reason) implements Preflight {}

        /** Something asked for what must not happen: the test fails, with nothing opened. */
        record Refuse(String reason) implements Preflight {}

        /**
         * A build that names the test environment where {@code CI} is set is refused, not skipped: a
         * skip would hide that the shared feed was about to be opened from a pipeline. Without a name
         * it is skipped, wherever it runs; a name that is not the test environment is an
         * {@link IllegalArgumentException}.
         *
         * @param lookup the system properties, then the environment, see {@link Settings#from}
         * @param ci the value of {@code CI}, null when it is not set
         * @param token the access token, null when it is not set
         */
        static Preflight decide(
                Function<String, @Nullable String> lookup, @Nullable String ci, @Nullable String token) {
            var selected = lookup.apply(Settings.SELECTOR);
            boolean named = selected != null && !selected.isBlank();
            if (ci != null) {
                return named
                        ? new Refuse("CI is set and " + Settings.SELECTOR
                                + " names the test environment: the shared feed is not opened from CI")
                        : new Skip("the test environment is not for CI");
            }
            var settings = Settings.from(lookup);
            if (settings.isEmpty()) {
                return new Skip(Settings.SELECTOR + "=test names the test environment; it is not set");
            }
            if (token == null || token.isBlank()) {
                return new Refuse("the test environment is named but there is no access token");
            }
            return new Open(settings.get());
        }
    }

    private static final Pattern ID = Pattern.compile("od:[a-z]+:\\d+");
    private static final Pattern VARIANT = Pattern.compile("/markets/\\d+/variants/[^/]+");

    private TestEnvironmentRestart() {}

    /** A path as one endpoint: no query, the ids and the variant left out. */
    static String endpoint(ApiCallEvent call) {
        var path = call.uri().getPath();
        path = VARIANT.matcher(path).replaceAll("/markets/{id}/variants/{variant}");
        path = ID.matcher(path).replaceAll("{id}");
        return call.method() + " " + path;
    }

    /** The results of one run, as the text of its report. */
    record Result(String report, boolean timedOut) {}

    /**
     * Opens a feed against the test environment, watches it until every session has stayed on time
     * for the settle time or the maximum is reached, and closes it.
     *
     * @param token the access token; used only to configure the SDK, never printed or kept
     */
    static Result run(Settings settings, String token) throws InterruptedException {
        var run = new Run(settings);
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectTest()
                .setAccessToken(token)
                .setEagerEntityPreload(settings.eager())
                // without a preload locale no catalog is loaded at open: a client sets them
                .setPreloadLocales(List.of(EN))
                .build();
        var feed = new OddsFeed(run.events, configuration);
        try (var telemetry = settings.sustained() ? RuntimeTelemetry.start(Duration.ofSeconds(1)) : null) {
            run.telemetry = telemetry;
            run.start = System.nanoTime();
            run.startInstant = Instant.now();
            for (var interest : settings.sessions()) {
                var session = new SessionRecorder(run, interest);
                run.sessions.add(session);
                feed.getSessionBuilder()
                        .setListener(session)
                        .setMessageInterest(interest)
                        .build();
            }
            feed.open();
            run.opened = System.nanoTime();
            // read after the open, which is when the feed knows which producers it takes
            run.expectedProducers =
                    Set.copyOf(feed.getProducerManager().getActiveProducers().keySet());
            run.expectedKnown = true;
            run.threadsAtOpen = RuntimeTelemetry.threadGroups();
            watch(feed, run);
            return new Result(report(feed, run), run.timedOut);
        } finally {
            feed.close();
        }
    }

    /** Samples the feed every second until it has settled or the maximum has passed. */
    private static void watch(OddsFeed feed, Run run) throws InterruptedException {
        long settle = run.settings.settle().toNanos();
        long max = run.settings.max().toNanos();
        while (true) {
            Thread.sleep(1000);
            long now = System.nanoTime() - run.start;
            var health = feed.getHealth();
            var depths = new ArrayList<Integer>();
            for (var session : health.sessions()) {
                depths.add(session.queueDepth());
            }
            run.depths.add(new Sample(now, depths, health.recovery().completed()));
            if (run.settings.sustained()) {
                // a sustained run does not end when the sessions are on time: it goes on for its duration
                if (run.healthSamples.isEmpty() || now - run.healthSamples.getLast().at >= HEALTH_EVERY) {
                    run.healthSamples.add(new HealthSample(
                            now, health.state().toString(), health.components().toString(), depths.toString()));
                }
                if (now >= run.settings.duration().toNanos()) {
                    return;
                }
                continue;
            }
            if (now > max) {
                run.timedOut = true;
                return;
            }
            long allUp = run.allProducersUpAt();
            if (allUp < 0) {
                continue;
            }
            // on time since `settle` ago, and nothing waiting for a callback
            long behind = allUp;
            for (var session : run.sessions) {
                behind = Math.max(behind, session.lastLateAt.get());
            }
            boolean empty = depths.stream().allMatch(depth -> depth <= 1);
            if (now - behind >= settle && empty) {
                return;
            }
        }
    }

    /** One sample of the sessions' queues. */
    private record Sample(long at, List<Integer> queueDepths, long recoveriesCompleted) {}

    /** The feed's health, as a sustained run reads it every minute. */
    private record HealthSample(long at, String state, String components, String queueDepths) {}

    private static final long HEALTH_EVERY = Duration.ofMinutes(1).toNanos();

    /** What a run collects, from the threads that feed it. */
    private static final class Run {
        final Settings settings;
        final List<SessionRecorder> sessions = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<ApiCallEvent> calls = new ConcurrentLinkedQueue<>();
        final List<String> log = new CopyOnWriteArrayList<>();
        final List<Sample> depths = new ArrayList<>();
        final Map<Long, Long> producersUpAt = new ConcurrentHashMap<>();
        final Events events = new Events(this);
        final Set<String> matches = ConcurrentHashMap.newKeySet();
        final List<HealthSample> healthSamples = new ArrayList<>();
        /** The least time from a message being made to the broker client getting it, in milliseconds. */
        final AtomicLong floor = new AtomicLong(Long.MAX_VALUE);

        volatile long start;
        volatile Instant startInstant = Instant.EPOCH;
        volatile long opened;
        volatile Set<Long> expectedProducers = Set.of();
        volatile boolean expectedKnown;
        volatile boolean timedOut;
        volatile @Nullable RuntimeTelemetry telemetry;
        volatile Map<String, Integer> threadsAtOpen = Map.of();

        Run(Settings settings) {
            this.settings = settings;
        }

        long since() {
            return System.nanoTime() - start;
        }

        void note(String what) {
            log.add(String.format("%8.3f s  %s", since() / 1e9, what));
        }

        /** When the last of the producers the feed takes came up for the first time; -1 while one has not. */
        long allProducersUpAt() {
            if (!expectedKnown || expectedProducers.isEmpty()) {
                return -1;
            }
            long latest = 0;
            for (var id : expectedProducers) {
                var at = producersUpAt.get(id);
                if (at == null) {
                    return -1;
                }
                latest = Math.max(latest, at);
            }
            return latest;
        }
    }

    /** The feed's own events: the producers, the API calls, and whatever went wrong. */
    private static final class Events implements GlobalEventsListener {
        private final Run run;

        Events(Run run) {
            this.run = run;
        }

        @Override
        public void onProducerStatusChange(ProducerStatus status) {
            var producer = status.getProducer();
            long id = producer == null ? -1 : producer.getId();
            if (!status.isDown()) {
                run.producersUpAt.putIfAbsent(id, run.since());
            }
            run.note("producer " + id + (status.isDown() ? " down" : " up") + ", " + status.getProducerStatusReason());
        }

        @Override
        public void onConnectionDown() {
            run.note("connection down");
        }

        @Override
        public void onConnectionStateChange(ConnectionStateChange change) {
            run.note("connection " + change);
        }

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}

        @Override
        public void onFatalError(String reason, @Nullable Throwable cause) {
            run.note("FATAL " + reason + (cause == null ? "" : ": " + cause));
        }

        @Override
        public void onApiCall(ApiCallEvent call) {
            run.calls.add(call);
        }

        @Override
        public void onCallbackFailure(CallbackFailure failure) {
            run.note("callback failure in " + failure.callback() + ": " + failure.exception());
        }

        @Override
        public void onSafetyNetEvent(SafetyNetEvent event) {
            run.note("safety net " + event.kind() + ", producer " + event.producerId() + ", age " + event.age());
        }

        @Override
        public void onSessionLagChange(SessionLagChange change) {
            run.note("session lagging=" + change.lagging());
        }

        @Override
        public void onHealthEvent(HealthEvent event) {
            run.note("health " + event.component() + ": " + event.previous() + " to " + event.state() + ", "
                    + event.reason());
        }
    }

    /** A growing array of longs, written by one thread and read under the recorder's lock. */
    private static final class Longs {
        private long[] values = new long[1024];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    /** One session's listener: records when and how long each message waited and how long its callback took. */
    private static final class SessionRecorder implements OddsFeedListener {
        final Run run;
        final MessageInterest interest;
        final AtomicLong lastLateAt = new AtomicLong(-1);
        private final Longs at = new Longs();
        private final Longs sdkWaits = new Longs();
        private final Longs ages = new Longs();
        private final Longs callbacks = new Longs();
        private final Longs reads = new Longs();
        private final Map<String, AtomicInteger> byType = new TreeMap<>();
        private final AtomicInteger oddsChanges = new AtomicInteger();

        SessionRecorder(Run run, MessageInterest interest) {
            this.run = run;
            this.interest = interest;
        }

        private synchronized void record(String type, Message message, long entered, long left) {
            var stamps = message.getTimestamp();
            long sinceStart = entered - run.start;
            // the SDK stamps a message when the broker client takes it off the wire and again as the
            // session hands it over, both by this machine's clock; what a message waited at the broker
            // before that is not in it, so the age is taken from the producer's stamp as well
            long inSdk = Math.max(0, stamps.getPublished() - stamps.getReceived());
            long made = stamps.getCreated() > 0 ? stamps.getCreated() : stamps.getSent();
            long age = -1;
            if (made > 0) {
                age = stamps.getPublished() - made;
                // what the producer's clock and the network add to every message, as the least it ever took
                long floor = run.floor.accumulateAndGet(stamps.getReceived() - made, Math::min);
                if (Math.max(0, age - floor) * 1_000_000
                        > run.settings.lagLimit().toNanos()) {
                    lastLateAt.set(sinceStart);
                }
            }
            at.add(sinceStart);
            sdkWaits.add(inSdk * 1_000_000);
            ages.add(age);
            callbacks.add(left - entered);
            byType.computeIfAbsent(type, _ -> new AtomicInteger()).incrementAndGet();
        }

        private synchronized void readNames(long nanos) {
            reads.add(nanos);
        }

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            long entered = System.nanoTime();
            oddsChanges.incrementAndGet();
            run.matches.add(String.valueOf(message.getEvent().getId()));
            if (run.settings.reads() == Reads.NAMES) {
                requireNonNull(message.getEvent().getName(EN));
                for (var market : message.getMarkets()) {
                    long read = System.nanoTime();
                    requireNonNull(market.getName(EN));
                    readNames(System.nanoTime() - read);
                }
            }
            record("odds_change", message, entered, System.nanoTime());
        }

        private void other(String type, Message message) {
            long entered = System.nanoTime();
            record(type, message, entered, entered);
        }

        @Override
        public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {
            other("bet_stop", message);
        }

        @Override
        public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {
            other("bet_settlement", message);
        }

        @Override
        public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {
            other("rollback_bet_settlement", message);
        }

        @Override
        public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {
            other("rollback_bet_cancel", message);
        }

        @Override
        public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {
            other("bet_cancel", message);
        }

        @Override
        public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
            other("fixture_change", message);
        }

        /** What each message spent in the SDK and in the callback, in nanoseconds, in handling order. */
        synchronized long[] through() {
            var waits = sdkWaits.toArray();
            var calls = callbacks.toArray();
            var sum = new long[waits.length];
            for (int i = 0; i < sum.length; i++) {
                sum[i] = waits[i] + calls[i];
            }
            return sum;
        }

        /** A row for each stretch of {@code window} of the run: the messages handled in it, and their waits and callbacks. */
        synchronized String overTime(long window, long total) {
            var when = at.toArray();
            var waits = sdkWaits.toArray();
            var calls = callbacks.toArray();
            var through = through();
            var out = new StringBuilder();
            out.append(String.format(
                    "%-14s %7s %9s %9s %9s %10s %10s %10s%n",
                    "window", "msgs", "wait p50", "wait p99", "wait max", "thru p99", "thru max", "call p99"));
            for (long from = 0; from < total; from += window) {
                long to = from + window;
                var wait = new ArrayList<Long>();
                var call = new ArrayList<Long>();
                var thru = new ArrayList<Long>();
                for (int i = 0; i < when.length; i++) {
                    if (when[i] >= from && when[i] < to) {
                        wait.add(waits[i]);
                        call.add(calls[i]);
                        thru.add(through[i]);
                    }
                }
                var w = wait.stream().mapToLong(Long::longValue).toArray();
                var c = call.stream().mapToLong(Long::longValue).toArray();
                var t = thru.stream().mapToLong(Long::longValue).toArray();
                out.append(String.format(
                        "%5.1f-%-5.1f min %7d %9s %9s %9s %10s %10s %10s%n",
                        from / 6e10,
                        to / 6e10,
                        w.length,
                        ms(RestartStats.percentile(w, 50)),
                        ms(RestartStats.percentile(w, 99)),
                        ms(RestartStats.percentile(w, 100)),
                        ms(RestartStats.percentile(t, 99)),
                        ms(RestartStats.percentile(t, 100)),
                        ms(RestartStats.percentile(c, 99))));
            }
            return out.toString();
        }

        /** The age of each message beyond the floor, in nanoseconds; none known counts as on time. */
        synchronized long[] adjustedAges(long floor) {
            var raw = ages.toArray();
            var adjusted = new long[raw.length];
            for (int i = 0; i < raw.length; i++) {
                adjusted[i] = raw[i] < 0 ? 0 : Math.max(0, raw[i] - floor) * 1_000_000;
            }
            return adjusted;
        }

        synchronized RestartStats.Waits waits(long floor) {
            return RestartStats.waits(
                    at.toArray(), adjustedAges(floor), run.settings.lagLimit().toNanos());
        }
    }

    // ---- the report

    private static String ms(long nanos) {
        return nanos >= 10_000_000_000L
                ? String.format(Locale.ROOT, "%.1f s", nanos / 1e9)
                : nanos >= 1_000_000_000L
                        ? String.format(Locale.ROOT, "%.2f s", nanos / 1e9)
                        : String.format(Locale.ROOT, "%d ms", Math.round(nanos / 1e6));
    }

    private static String report(OddsFeed feed, Run run) {
        var out = new StringBuilder();
        var settings = run.settings;
        out.append("== restart against the test environment: ")
                .append(settings.label())
                .append(" ==\n");
        out.append("started ")
                .append(run.startInstant)
                .append(", sdk ")
                .append(OddsFeed.getSdkVersion())
                .append("\n");
        out.append("reads=").append(settings.reads()).append(" eager=").append(settings.eager());
        out.append(" sessions=").append(settings.sessions());
        out.append(" lagLimit=").append(settings.lagLimit().toSeconds()).append("s");
        out.append(" settle=").append(settings.settle().toSeconds()).append("s");
        out.append(" max=").append(settings.max().toMinutes()).append("min\n");
        out.append("open() returned after ").append(ms(run.opened - run.start)).append("\n");
        if (run.timedOut) {
            out.append("NOT SETTLED within the maximum\n");
        }

        // the API as the SDK saw it, before the report's own reads add calls
        var calls = new ArrayList<>(run.calls);
        out.append("\n-- API calls as the SDK saw them (every attempt; ")
                .append(calls.size())
                .append(" in all)\n");
        out.append(String.format(
                "%-62s %6s %5s %5s %8s %8s %8s %8s %9s %9s%n",
                "endpoint", "n", "retry", "fail", "p50", "p90", "p99", "max", "first", "last"));
        var byEndpoint = new TreeMap<String, List<ApiCallEvent>>();
        for (var call : calls) {
            byEndpoint.computeIfAbsent(endpoint(call), _ -> new ArrayList<>()).add(call);
        }
        long variantCalls = 0;
        var variants = new HashSet<String>();
        for (var entry : byEndpoint.entrySet()) {
            var list = entry.getValue();
            var latencies = list.stream().mapToLong(c -> c.latency().toNanos()).toArray();
            long first = Long.MAX_VALUE;
            long last = 0;
            int retries = 0;
            int failed = 0;
            for (var call : list) {
                long ended = Duration.between(run.startInstant, call.at()).toNanos();
                last = Math.max(last, ended);
                first = Math.min(first, ended - call.latency().toNanos());
                retries += call.attempt() > 1 ? 1 : 0;
                failed += call.failure() != null || call.status() / 100 != 2 ? 1 : 0;
                if (call.uri().getPath().contains("/variants/")) {
                    variantCalls++;
                    variants.add(call.uri().getPath());
                }
            }
            out.append(String.format(
                    "%-62s %6d %5d %5d %8s %8s %8s %8s %9s %9s%n",
                    entry.getKey(),
                    list.size(),
                    retries,
                    failed,
                    ms(RestartStats.percentile(latencies, 50)),
                    ms(RestartStats.percentile(latencies, 90)),
                    ms(RestartStats.percentile(latencies, 99)),
                    ms(RestartStats.percentile(latencies, 100)),
                    ms(first),
                    ms(last)));
        }
        out.append("(first and last: from the start of the run to the first request sent and the last answer)\n");

        out.append("\n-- producers\n");
        var up = new TreeMap<>(run.producersUpAt);
        out.append("taken by the feed: ")
                .append(new TreeSet<>(run.expectedProducers))
                .append("\n");
        up.forEach((id, at) -> out.append("producer ")
                .append(id)
                .append(" first up at ")
                .append(ms(at))
                .append("\n"));
        long allUp = run.allProducersUpAt();
        out.append("every producer up at ")
                .append(allUp < 0 ? "never" : ms(allUp))
                .append("\n");
        var health = feed.getHealth();
        var recovery = health.recovery();
        out.append("recoveries requested=")
                .append(recovery.requested())
                .append(" completed=")
                .append(recovery.completed())
                .append(" failed=")
                .append(recovery.failed())
                .append(" timedOut=")
                .append(recovery.timedOut())
                .append(" safetyNetResets=")
                .append(recovery.safetyNetResets())
                .append("\n");

        long floorMs = run.floor.get() == Long.MAX_VALUE ? 0 : run.floor.get();
        out.append("\n-- sessions (late = older than ")
                .append(settings.lagLimit().toSeconds())
                .append(
                        " s when the callback started, beyond the least a message ever took from the producer to this client: ")
                .append(floorMs)
                .append(" ms, which is the producer's clock and the network)\n");
        int index = 0;
        long lastCaughtUp = -1;
        for (var session : run.sessions) {
            var waits = session.waits(floorMs);
            long caught = waits.caughtUpAt();
            lastCaughtUp = Math.max(lastCaughtUp, caught);
            long[] waitValues;
            long[] ageValues = session.adjustedAges(floorMs);
            long[] callbackValues;
            long[] readValues;
            synchronized (session) {
                waitValues = session.sdkWaits.toArray();
                callbackValues = session.callbacks.toArray();
                readValues = session.reads.toArray();
            }
            out.append("session ")
                    .append(++index)
                    .append(" ")
                    .append(session.interest)
                    .append(": ")
                    .append(waits.messages())
                    .append(" messages ")
                    .append(session.byType)
                    .append(", late ")
                    .append(waits.late())
                    .append(", first handled at ")
                    .append(waits.messages() == 0 ? "-" : ms(waits.firstAt()))
                    .append(", caught up at ")
                    .append(caught < 0 ? "never (no message)" : ms(caught))
                    .append(waits.late() == 0 ? " (never behind)" : "")
                    .append("\n");
            out.append("  wait in the SDK, from the broker client to the callback: p50 ")
                    .append(ms(RestartStats.percentile(waitValues, 50)))
                    .append(", p99 ")
                    .append(ms(RestartStats.percentile(waitValues, 99)))
                    .append(", max ")
                    .append(ms(RestartStats.percentile(waitValues, 100)))
                    .append("; age beyond the floor: p50 ")
                    .append(ms(RestartStats.percentile(ageValues, 50)))
                    .append(", p99 ")
                    .append(ms(RestartStats.percentile(ageValues, 99)))
                    .append(", max ")
                    .append(ms(RestartStats.percentile(ageValues, 100)))
                    .append("\n");
            out.append("  callbacks: p50 ")
                    .append(ms(RestartStats.percentile(callbackValues, 50)))
                    .append(", p99 ")
                    .append(ms(RestartStats.percentile(callbackValues, 99)))
                    .append(", longest ")
                    .append(ms(RestartStats.percentile(callbackValues, 100)));
            if (settings.reads() == Reads.NAMES) {
                long total = Arrays.stream(readValues).sum();
                long slow =
                        Arrays.stream(readValues).filter(n -> n > 20_000_000L).count();
                out.append("; market names read ")
                        .append(readValues.length)
                        .append(", in ")
                        .append(ms(total))
                        .append(" in all, longest ")
                        .append(ms(RestartStats.percentile(readValues, 100)))
                        .append(", over 20 ms: ")
                        .append(slow);
            }
            out.append("\n");
        }
        out.append("every session caught up at ")
                .append(lastCaughtUp < 0 ? "-" : ms(lastCaughtUp))
                .append(", ")
                .append(lastCaughtUp < 0 ? "-" : ms(Math.max(0, lastCaughtUp - Math.max(0, allUp))))
                .append(" after every producer was up\n");
        int peak = 0;
        int deepest = 0;
        for (var sample : run.depths) {
            for (var depth : sample.queueDepths()) {
                if (depth > peak) {
                    peak = depth;
                    deepest = (int) (sample.at() / 1_000_000_000L);
                }
            }
        }
        out.append("deepest session queue sampled: ")
                .append(peak)
                .append(" at ")
                .append(deepest)
                .append(" s (sampled every second; the prefetch bounds it)\n");

        out.append("\n-- variants\n");
        out.append("variant requests: ")
                .append(variantCalls)
                .append(", distinct ")
                .append(variants.size())
                .append("\n");

        if (settings.sustained()) {
            sustained(out, run);
        }

        out.append("\n-- health at the end\n");
        out.append("state ")
                .append(health.state())
                .append(" ")
                .append(health.components())
                .append("\n");
        out.append("events dropped: telemetry ")
                .append(health.events().telemetryDropped())
                .append(", control ")
                .append(health.events().controlDropped())
                .append(" (a telemetry drop means the API table above misses calls)\n");
        out.append("side loads dropped ")
                .append(health.caches().sideLoadsDropped())
                .append(", failed ")
                .append(health.caches().sideLoadsFailed())
                .append("\n");
        for (var catalog : health.catalogs()) {
            out.append("catalog ")
                    .append(catalog.name())
                    .append(": ")
                    .append(catalog.state())
                    .append(", failed fetches ")
                    .append(catalog.failedFetches())
                    .append("\n");
        }

        out.append("\n-- the feed's own events\n");
        run.log.forEach(line -> out.append(line).append("\n"));

        out.append("\n-- facts about the environment (kept out of the repository)\n");
        out.append("distinct matches in the odds changes: ")
                .append(run.matches.size())
                .append("\n");
        facts(feed, out);
        return out.toString();
    }

    /**
     * What only a sustained run has: the time through a session (the wait in the SDK and the callback)
     * over the whole run and window by window, the feed's health every minute, and what the JVM spent.
     */
    private static void sustained(StringBuilder out, Run run) {
        long duration = run.settings.duration().toNanos();
        long window = duration / 12;
        out.append("\n-- sustained: the time through a session, SDK wait plus callback\n");
        int index = 0;
        for (var session : run.sessions) {
            var through = session.through();
            out.append("session ")
                    .append(++index)
                    .append(" ")
                    .append(session.interest)
                    .append(": ")
                    .append(through.length)
                    .append(" messages, p50 ")
                    .append(ms(RestartStats.percentile(through, 50)))
                    .append(", p90 ")
                    .append(ms(RestartStats.percentile(through, 90)))
                    .append(", p99 ")
                    .append(ms(RestartStats.percentile(through, 99)))
                    .append(", p99.9 ")
                    .append(ms(RestartStats.percentile(through, 99.9)))
                    .append(", max ")
                    .append(ms(RestartStats.percentile(through, 100)))
                    .append("\n");
            out.append(session.overTime(window, duration));
        }
        out.append("\n-- sustained: the feed's health, every minute\n");
        long notHealthy = run.healthSamples.stream()
                .filter(sample -> !sample.state.equals("HEALTHY"))
                .count();
        out.append(run.healthSamples.size())
                .append(" samples, ")
                .append(notHealthy)
                .append(" not healthy\n");
        for (var sample : run.healthSamples) {
            out.append(String.format("%8.1f min  %s  queues %s", sample.at / 6e10, sample.state, sample.queueDepths));
            out.append(sample.state.equals("HEALTHY") ? "" : "  " + sample.components)
                    .append("\n");
        }
        out.append("\n-- sustained: the JVM (this test's: the SDK, the harness and nothing else)\n");
        var telemetry = run.telemetry;
        if (telemetry == null) {
            return;
        }
        var log = System.getProperty("benchmarks.gclog");
        var pauses = List.<GcLog.Pause>of();
        if (log == null) {
            out.append("no GC log: run the JVM with -Xlog:gc:file=<path>:uptime and -Dbenchmarks.gclog=<path>\n");
        } else {
            try {
                pauses = GcLog.pauses(Path.of(log), telemetry.uptimeAtStartSeconds());
            } catch (IOException e) {
                out.append("the GC log could not be read: ").append(e).append("\n");
            }
        }
        out.append(telemetry.summary(pauses, 12));
        out.append("threads by name at the open: ").append(run.threadsAtOpen).append("\n");
        out.append("threads by name now: ")
                .append(RuntimeTelemetry.threadGroups())
                .append("\n");
        out.append("\n-- sustained: samples (seconds, bytes allocated, CPU seconds, threads, heap in use)\n");
        out.append(telemetry.csv());
    }

    /** What the SDK's managers say of the environment, read last: a read may call the API, and does so after the table above. */
    private static void facts(OddsFeed feed, StringBuilder out) {
        try {
            out.append("producers listed: ")
                    .append(feed.getProducerManager().getAvailableProducers().size())
                    .append(", taken ")
                    .append(feed.getProducerManager().getActiveProducers().size())
                    .append("\n");
            out.append("market descriptions: ")
                    .append(requireNonNull(feed.getMarketDescriptionManager().getMarketDescriptions(EN))
                            .size())
                    .append("\n");
            out.append("sports: ")
                    .append(requireNonNull(feed.getSportsInfoManager().getSports(EN))
                            .size())
                    .append("\n");
            long started = System.nanoTime();
            var live = requireNonNull(feed.getSportsInfoManager().getLiveMatches(EN));
            out.append("live matches by the API: ")
                    .append(live.size())
                    .append(" (the call took ")
                    .append(ms(System.nanoTime() - started))
                    .append(")\n");
        } catch (RuntimeException e) {
            out.append("a fact could not be read: ").append(e).append("\n");
        }
    }
}
