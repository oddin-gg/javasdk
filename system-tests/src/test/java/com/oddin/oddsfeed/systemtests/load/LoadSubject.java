package com.oddin.oddsfeed.systemtests.load;

import com.oddin.oddsfeed.benchmarks.GcLog;
import com.oddin.oddsfeed.benchmarks.RuntimeTelemetry;
import com.oddin.oddsfeed.benchmarks.Stats;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsDisplayType;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The SDK under load, in a JVM of its own, driven only through the public API the 0.0.x line and
 * 1.0 share: what {@link LoadHarnessIT} measures of one or the other. A JVM of its own because the
 * allocation, the GC and the CPU it reports must be the SDK's and the listener's, not those of the
 * broker's client, the fake API and the publisher that make the load.
 *
 * <p>Talks to its parent over stdin and stdout: lines starting with {@code @} on stdout mark
 * progress ({@code @T0} just before the feed is built, {@code @OPENED}, {@code @UP <producer>},
 * {@code @BURST_DONE} once the expected odds changes were handled, {@code @DONE}); the parent writes
 * {@code measure} to begin the measured stretch and {@code stop} to end the run, and the report goes
 * to the file named by {@code out}. Settings are {@code key=value} arguments.
 */
public final class LoadSubject {

    private static final Locale EN = Locale.ENGLISH;

    /** What the callbacks read of an odds change. */
    enum Reads {
        /** The match's id only. */
        NOTHING,
        /** The match's id, and every market's id and every outcome's odds: what a client forwarding prices does. */
        PRICES,
        /** The match's name, as a client showing it does. */
        NAME,
        /** Prices, and the match's name and the name of every market. */
        NAMES
    }

    private LoadSubject() {}

    public static void main(String[] args) throws Exception {
        var options = options(args);
        var reads = Reads.valueOf(options.getOrDefault("reads", "prices").toUpperCase(Locale.ROOT));
        var interest =
                MessageInterest.valueOf(options.getOrDefault("interest", "all").toUpperCase(Locale.ROOT));
        var out = Path.of(required(options, "out"));
        int expect = Integer.parseInt(options.getOrDefault("expect", "0"));
        boolean atStart = Boolean.parseBoolean(options.getOrDefault("measureAtStart", "false"));
        var gcLog = options.get("gcLog");

        var subject = new Run(reads, expect);
        subject.mix = options.getOrDefault("mix", "");
        if (atStart) {
            subject.begin();
        }
        subject.t0 = System.nanoTime();
        say("@T0");
        var configuration = configure(
                OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment(
                                required(options, "feedHost"),
                                required(options, "apiHost"),
                                Integer.parseInt(required(options, "feedPort")))
                        .setAccessToken("load-subject"),
                Boolean.parseBoolean(options.getOrDefault("preload", "true")));
        var feed = new OddsFeed(subject.events, configuration);
        feed.getSessionBuilder()
                .setListener(subject.recorder)
                .setMessageInterest(interest)
                .build();
        feed.open();
        subject.opened = System.nanoTime();
        say("@OPENED");

        try (var stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (var command = stdin.readLine(); command != null; command = stdin.readLine()) {
                if (command.equals("measure")) {
                    subject.begin();
                } else if (command.equals("stop")) {
                    break;
                }
            }
        }
        subject.end();
        var report = subject.report(gcLog);
        feed.close();
        Files.writeString(out, report);
        say("@DONE");
        System.exit(0);
    }

    /** Settings the 0.0.x builder lacks are set through reflection, where the builder has them. */
    private static OddsFeedConfiguration configure(OddsFeedConfigurationBuilder builder, boolean preload)
            throws ReflectiveOperationException {
        if (preload) {
            var type = builder.getClass();
            Method eager;
            try {
                eager = type.getMethod("setEagerEntityPreload", boolean.class);
            } catch (NoSuchMethodException legacy) {
                // 0.0.x has no preload to switch on
                return builder.build();
            }
            eager.invoke(builder, true);
            // a builder that has the one has the other: without it the preload would be silently off
            type.getMethod("setPreloadLocales", List.class).invoke(builder, List.of(EN));
        }
        return builder.build();
    }

    private static void say(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static Map<String, String> options(String[] args) {
        var options = new TreeMap<String, String>();
        for (var arg : args) {
            int eq = arg.indexOf('=');
            options.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        var value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("no " + key + " given");
        }
        return value;
    }

    /** A growing array of longs. */
    private static final class Series {
        private long[] values = new long[4096];
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

        void clear() {
            size = 0;
        }
    }

    /** One run: the listeners, what they recorded and the report. */
    private static final class Run {
        final Reads reads;
        final int expect;
        final Recorder recorder = new Recorder(this);
        final Events events = new Events(this);
        final Map<Long, Long> producersUp = new ConcurrentHashMap<>();
        final AtomicInteger oddsChanges = new AtomicInteger();
        volatile long t0;
        volatile long opened;
        volatile long burstDoneAt;
        volatile long measureFrom;
        volatile long measureTo;
        volatile boolean measuring;
        String mix = "";
        private RuntimeTelemetry telemetry;

        Run(Reads reads, int expect) {
            this.reads = reads;
            this.expect = expect;
        }

        void begin() {
            synchronized (recorder) {
                recorder.clear();
            }
            telemetry = RuntimeTelemetry.start(Duration.ofSeconds(1));
            measureFrom = System.nanoTime();
            measuring = true;
        }

        void end() {
            measuring = false;
            measureTo = System.nanoTime();
            if (telemetry != null) {
                telemetry.close();
            }
        }

        String report(String gcLog) throws IOException {
            var out = new StringBuilder();
            var location = OddsFeed.class.getProtectionDomain().getCodeSource().getLocation();
            out.append("== subject: ").append(location).append(" ==\n");
            out.append("jvm ")
                    .append(System.getProperty("java.version"))
                    .append(", ")
                    .append(ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                            .filter(argument -> argument.startsWith("-Xm") || argument.startsWith("-XX"))
                            .toList())
                    .append(", reads=")
                    .append(reads)
                    .append(", ")
                    .append(mix)
                    .append("\n");
            out.append(String.format(
                    Locale.ROOT,
                    "feed opened %d ms after the start; producers up at %s ms%s%n",
                    (opened - t0) / 1_000_000,
                    new TreeMap<>(upAt()),
                    burstDoneAt == 0
                            ? ""
                            : "; the " + expect + " expected odds changes were handled at "
                                    + (burstDoneAt - t0) / 1_000_000 + " ms"));
            double seconds = (measureTo - measureFrom) / 1e9;
            out.append(String.format(
                    Locale.ROOT,
                    "measured over %.1f s, from %s%n",
                    seconds,
                    java.time.Instant.now().minusNanos(System.nanoTime() - measureFrom)));
            out.append(recorder.report(measureTo - measureFrom));
            if (telemetry != null && telemetry.ticks().size() > 1) {
                var pauses = List.<GcLog.Pause>of();
                if (gcLog != null) {
                    pauses = GcLog.pauses(Path.of(gcLog), telemetry.uptimeAtStartSeconds());
                }
                out.append("\n").append(telemetry.summary(pauses, 10));
                var ticks = telemetry.ticks();
                double allocated =
                        ticks.getLast().allocatedBytes() - ticks.getFirst().allocatedBytes();
                double cpu = ticks.getLast().cpuNanos() - ticks.getFirst().cpuNanos();
                int messages = recorder.count();
                if (messages > 0) {
                    out.append(String.format(
                            Locale.ROOT,
                            "per message handled (%d): %.1f KB allocated, %.0f us of process CPU%n",
                            messages,
                            allocated / messages / 1024,
                            cpu / messages / 1000));
                }
            }
            out.append("threads by name: ")
                    .append(RuntimeTelemetry.threadGroups())
                    .append("\n");
            return out.toString();
        }

        private Map<Long, Long> upAt() {
            var at = new TreeMap<Long, Long>();
            producersUp.forEach((id, nanos) -> at.put(id, (nanos - t0) / 1_000_000));
            return at;
        }
    }

    /** The feed-wide events: when each producer came up. */
    private static final class Events implements GlobalEventsListener {
        private final Run run;

        Events(Run run) {
            this.run = run;
        }

        @Override
        public void onProducerStatusChange(ProducerStatus status) {
            if (status.getProducer() != null && !status.isDown()) {
                long id = status.getProducer().getId();
                if (run.producersUp.putIfAbsent(id, System.nanoTime()) == null) {
                    say("@UP " + id);
                }
            }
        }

        @Override
        public void onConnectionDown() {
            System.err.println("connection down");
        }

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }

    /** The session's listener: what each message took, by the clock of this machine. */
    private static final class Recorder implements OddsFeedListener {
        private final Run run;
        private final Series at = new Series();
        private final Series age = new Series();
        private final Series wait = new Series();
        private final Series toCallback = new Series();
        private final Series arrival = new Series();
        private final Series callback = new Series();
        private final Map<String, AtomicInteger> byType = new ConcurrentHashMap<>();
        private String sample = "";
        private long sink;

        Recorder(Run run) {
            this.run = run;
        }

        synchronized void clear() {
            at.clear();
            age.clear();
            wait.clear();
            toCallback.clear();
            arrival.clear();
            callback.clear();
            byType.clear();
        }

        synchronized int count() {
            return at.toArray().length;
        }

        private synchronized void record(String type, Message message, long entered, long left, long enteredMillis) {
            if (!run.measuring) {
                return;
            }
            var stamps = message.getTimestamp();
            if (sample.isEmpty()) {
                sample = "created " + stamps.getCreated() + ", sent " + stamps.getSent() + ", received "
                        + stamps.getReceived() + ", published " + stamps.getPublished() + ", callback at "
                        + System.currentTimeMillis();
            }
            at.add(entered - run.measureFrom);
            // the producers' stamp is this machine's clock too: the publisher is another JVM on it; the age is
            // read at the callback's first line, as the receipt's wait is, not after what the callback read
            age.add(stamps.getCreated() > 0 ? Math.max(0, enteredMillis - stamps.getCreated()) : -1);
            wait.add(
                    stamps.getReceived() > 0 && stamps.getPublished() >= stamps.getReceived()
                            ? stamps.getPublished() - stamps.getReceived()
                            : -1);
            // the same two points in both lines: the receipt, and the callback's first line
            toCallback.add(stamps.getReceived() > 0 ? Math.max(0, enteredMillis - stamps.getReceived()) : -1);
            // the age is this - before the SDK has the message - and the receipt to the callback
            arrival.add(
                    stamps.getCreated() > 0 && stamps.getReceived() > 0
                            ? Math.max(0, stamps.getReceived() - stamps.getCreated())
                            : -1);
            callback.add(left - entered);
            byType.computeIfAbsent(type, _ -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            long entered = System.nanoTime();
            long enteredMillis = System.currentTimeMillis();
            long seen = message.getEvent().getId().hashCode();
            if (run.reads == Reads.NAME || run.reads == Reads.NAMES) {
                seen += message.getEvent().getName(EN).length();
            }
            if (run.reads == Reads.PRICES || run.reads == Reads.NAMES) {
                for (var market : message.getMarkets()) {
                    seen += market.getId();
                    if (run.reads == Reads.NAMES) {
                        seen += market.getName(EN).length();
                    }
                    for (var outcome : market.getOutcomeOdds()) {
                        var odds = outcome.getOdds(OddsDisplayType.DECIMAL);
                        seen += odds == null ? 0 : odds.hashCode();
                    }
                }
            }
            long left = System.nanoTime();
            synchronized (this) {
                sink += seen;
            }
            record("odds_change", message, entered, left, enteredMillis);
            if (run.expect > 0 && run.oddsChanges.incrementAndGet() == run.expect) {
                run.burstDoneAt = left;
                say("@BURST_DONE");
            }
        }

        private void other(String type, Message message) {
            long entered = System.nanoTime();
            record(type, message, entered, entered, System.currentTimeMillis());
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

        @Override
        public void onUnparsableMessage(OddsFeedSession session, UnparsableMessage<SportEvent> message) {
            other("unparsable", message);
        }

        /** The messages handled in the measured stretch: how long they took to reach the callback, window by window. */
        synchronized String report(long measured) {
            var out = new StringBuilder();
            var when = at.toArray();
            var ages = age.toArray();
            var waits = wait.toArray();
            var calls = callback.toArray();
            var toCalls = toCallback.toArray();
            var arrivals = arrival.toArray();
            out.append("messages handled ")
                    .append(when.length)
                    .append(" ")
                    .append(new TreeMap<>(byType))
                    .append("\n");
            out.append("timestamps of the first message: ").append(sample).append("\n");
            // what the callbacks read, summed, so that the reads cannot be dropped as dead code
            out.append("checksum of what the callbacks read: ").append(sink).append("\n");
            out.append(summary("age: from the producer's stamp to the callback", ages, 1_000_000));
            out.append(summary("wait in the SDK: received to published", waits, 1_000_000));
            out.append(summary("arrival: from the producer's stamp to the receipt", arrivals, 1_000_000));
            out.append(summary("receipt to the callback: received to the callback's first line", toCalls, 1_000_000));
            out.append(summary("callback", calls, 1));
            int windows = 10;
            long window = Math.max(1, measured / windows);
            // short runs, a restart, are read in seconds
            boolean seconds = measured < 300_000_000_000L;
            double unit = seconds ? 1e9 : 6e10;
            out.append(String.format(
                    Locale.ROOT,
                    "%-14s %8s %9s %9s %9s %9s %9s%n",
                    seconds ? "window (s)" : "window (min)",
                    "msgs/s",
                    "age p50",
                    "age p99",
                    "age max",
                    "wait p99",
                    "wait max"));
            for (int w = 0; w < windows; w++) {
                var inside = new ArrayList<Integer>();
                for (int i = 0; i < when.length; i++) {
                    if (when[i] >= w * window && when[i] < (w + 1) * window) {
                        inside.add(i);
                    }
                }
                var a = inside.stream().mapToLong(i -> ages[i]).toArray();
                var s = inside.stream().mapToLong(i -> waits[i]).toArray();
                out.append(String.format(
                        Locale.ROOT,
                        "%6.1f-%-6.1f %8.0f %6d ms %6d ms %6d ms %6d ms %6d ms%n",
                        w * window / unit,
                        (w + 1) * window / unit,
                        inside.size() / (window / 1e9),
                        Stats.percentile(a, 50),
                        Stats.percentile(a, 99),
                        Stats.percentile(a, 100),
                        Stats.percentile(s, 99),
                        Stats.percentile(s, 100)));
            }
            return out.toString();
        }

        private static String summary(String what, long[] values, long unitNanos) {
            // -1 is "not known"
            var known = Arrays.stream(values).filter(v -> v >= 0).toArray();
            double scale = 1e6 / unitNanos;
            return String.format(
                    Locale.ROOT,
                    "%s: %d known, p50 %.1f ms, p90 %.1f ms, p99 %.1f ms, p99.9 %.1f ms, max %.1f ms%n",
                    what,
                    known.length,
                    Stats.percentile(known, 50) / scale,
                    Stats.percentile(known, 90) / scale,
                    Stats.percentile(known, 99) / scale,
                    Stats.percentile(known, 99.9) / scale,
                    Stats.percentile(known, 100) / scale);
        }
    }
}
