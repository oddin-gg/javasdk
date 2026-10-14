package com.oddin.oddsfeed.systemtests.load;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.oddin.oddsfeed.benchmarks.Corpus;
import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.fakes.TestTls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Measures the SDK on the classpath - the published 0.0.x by default, 1.0 with {@code -Dsdk.next} -
 * under the load of a restart and of a steady rate, through the public API both lines share, against
 * the fake feed and the fake API with the benchmarks' production-sized catalogs. The same code
 * measures both, so the numbers compare.
 *
 * <p>The SDK runs in a JVM of its own ({@link LoadSubject}) with a fixed heap and the GC log on, so
 * that the allocation, the GC pauses and the CPU it reports are its own; this JVM makes the load.
 * Both share the machine, which the report says so as not to be read as a bare-metal figure.
 *
 * <p>Skipped unless asked for and never run where {@code CI} is set: {@code SYSTEMTESTS_LOAD} names the
 * scenarios, {@code restart}, {@code rate} or both, and the settings are environment variables (or the
 * system properties of the same names in lower case with dots): {@code SYSTEMTESTS_LOAD_MATCHES} (cold matches of
 * the restart, 200), {@code _LATENCY} (REST latency in ms, 50), {@code _RATE} (messages a second, 1000), {@code
 * _DURATION} and {@code _WARMUP} (seconds, 600 and 60), {@code _MIX} (odds change sizes, {@code
 * 20:70,150:28,500:2}: markets and weight), {@code _READS} ({@code nothing}, {@code prices}, {@code name}, {@code
 * names}), {@code _HEAP} (the SDK's JVM, MB, 1024), {@code _LABEL}. The reports go to the console and to
 * {@code target/load/}.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LoadHarnessIT {

    private static final String PRE_RECOVERY = "/v1/pre/recovery/initiate_request";
    private static final String LIVE_RECOVERY = "/v1/live/recovery/initiate_request";
    private static final long FIRST_COLD_MATCH = 5_000_000;

    private static boolean selected(String scenario) {
        var asked = setting("", "");
        return System.getenv("CI") == null
                && !asked.isBlank()
                && (asked.contains(scenario) || asked.equalsIgnoreCase("all"));
    }

    /** {@code SYSTEMTESTS_LOAD_<KEY>} or the property {@code systemtests.load.<key>}; {@code fallback} when neither is set. */
    private static String setting(String key, String fallback) {
        var name = key.isEmpty() ? "systemtests.load" : "systemtests.load." + key;
        var value = System.getProperty(name);
        if (value == null) {
            value = System.getenv(name.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int number(String key, int fallback) {
        return Integer.parseInt(setting(key, Integer.toString(fallback)));
    }

    private static String label() {
        return setting("label", "sdk-" + System.getProperty("sdk.version", "unknown"));
    }

    /**
     * A restart: the feed opens, both producers say they are alive, the SDK asks for the recoveries, and
     * a snapshot of cold matches arrives before the producers' snapshot completes - as it does when a
     * client restarts with live matches. Measures the time to the producers up and to the matches handled.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRestartWithColdMatches() throws Exception {
        assumeTrue(selected("restart"), "SYSTEMTESTS_LOAD=restart (or rate, or all) asks for a load run");
        int matches = number("matches", 200);
        var latency = Duration.ofMillis(number("latency", 50));
        var burst = coldBurst(matches);

        try (var broker = FakeFeed.start();
                var api = FakeRestServer.start()) {
            api.respondWith(LoadApi.answers(latency));
            var timeline = new Timeline();
            try (var subject = Subject.start(
                    broker,
                    api,
                    label() + "-restart",
                    Map.of(
                            "reads",
                            setting("reads", "name"),
                            "expect",
                            Integer.toString(matches),
                            "measureAtStart",
                            "true",
                            "preload",
                            "true",
                            "mix",
                            "restart of " + matches + " cold matches, REST " + latency.toMillis() + " ms"))) {
                timeline.start = subject.await("T0", Duration.ofMinutes(1));
                subject.await("OPENED", Duration.ofMinutes(2));
                timeline.mark("feed open() returned");

                broker.publish(alive(1, true));
                broker.publish(alive(2, true));
                timeline.mark("alives published");
                var pre = api.awaitRequests("POST", PRE_RECOVERY, 1).getFirst();
                var live = api.awaitRequests("POST", LIVE_RECOVERY, 1).getFirst();
                timeline.mark("both recovery requests seen by the API");
                LoadPublisher.burst(broker, burst);
                timeline.mark("the " + matches + " odds changes published");
                broker.publish(snapshotComplete(1, requestId(pre)));
                broker.publish(snapshotComplete(2, requestId(live)));
                timeline.mark("snapshot completes published");

                timeline.mark("producer 1 up", subject.await("UP 1", Duration.ofMinutes(5)));
                timeline.mark("producer 2 up", subject.await("UP 2", Duration.ofMinutes(5)));
                timeline.mark("odds changes handled", subject.await("BURST_DONE", Duration.ofMinutes(10)));
                api.awaitQuiet();
                Thread.sleep(3_000);
                subject.stop();

                var out = new StringBuilder();
                out.append("== load: restart, ").append(label()).append(" ==\n");
                out.append("started ")
                        .append(Instant.now())
                        .append("; ")
                        .append(machine())
                        .append("\n");
                out.append("the API answers every request after ")
                        .append(latency.toMillis())
                        .append(" ms\n");
                out.append(timeline.report());
                out.append(requests(api.requests()));
                out.append("most requests answered at once: ")
                        .append(api.mostInFlight())
                        .append("\n\n");
                out.append(subject.report());
                finish(label() + "-restart", out.toString());
            }
        }
    }

    /**
     * A steady rate: the producers are up, and odds changes of a mix of sizes arrive at {@code
     * SYSTEMTESTS_LOAD_RATE} a second for the warm-up and then the measured duration. Measures how long a
     * message takes to reach the callback, and what the SDK's JVM allocates, pauses for and uses.
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.HOURS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSteadyRate() throws Exception {
        assumeTrue(selected("rate"), "SYSTEMTESTS_LOAD=rate (or restart, or all) asks for a load run");
        int rate = number("rate", 1000);
        int duration = number("duration", 600);
        int warmup = number("warmup", 60);
        var mix = setting("mix", "20:70,150:28,500:2");
        var pool = LoadPublisher.pool(mix, 4000, number("rateMatches", 400), 70);

        try (var broker = FakeFeed.start();
                var api = FakeRestServer.start()) {
            api.respondWith(LoadApi.answers(Duration.ofMillis(number("latency", 50))));
            try (var subject = Subject.start(
                    broker,
                    api,
                    label() + "-rate",
                    Map.of(
                            "reads",
                            setting("reads", "prices"),
                            "mix",
                            rate + "/s, sizes " + mix + " (markets:weight), 70 % live",
                            "preload",
                            "true"))) {
                subject.await("T0", Duration.ofMinutes(1));
                subject.await("OPENED", Duration.ofMinutes(2));
                broker.publish(alive(1, true));
                broker.publish(alive(2, true));
                var pre = api.awaitRequests("POST", PRE_RECOVERY, 1).getFirst();
                var live = api.awaitRequests("POST", LIVE_RECOVERY, 1).getFirst();
                broker.publish(snapshotComplete(1, requestId(pre)));
                broker.publish(snapshotComplete(2, requestId(live)));
                subject.await("UP 1", Duration.ofMinutes(5));
                subject.await("UP 2", Duration.ofMinutes(5));

                String publisherReport;
                long beforeMeasure;
                long afterMeasure;
                var backlog = new Backlog(broker);
                try (var publisher = new LoadPublisher(broker, pool, rate, warmup + duration + 120)) {
                    publisher.start(Math.max(2, rate / 250));
                    Thread.sleep(Duration.ofSeconds(warmup));
                    subject.send("measure");
                    beforeMeasure = publisher.published();
                    backlog.start();
                    Thread.sleep(Duration.ofSeconds(duration));
                    afterMeasure = publisher.published();
                    publisherReport = publisher.report();
                    backlog.stop();
                }
                // what is still on its way to the callback
                Thread.sleep(5_000);
                subject.stop();

                var out = new StringBuilder();
                out.append("== load: steady rate, ").append(label()).append(" ==\n");
                out.append("started ")
                        .append(Instant.now())
                        .append("; ")
                        .append(machine())
                        .append("\n");
                out.append("warm-up ")
                        .append(warmup)
                        .append(" s, measured ")
                        .append(duration)
                        .append(" s\n");
                out.append(publisherReport).append("\n");
                out.append("published in the measured stretch: ")
                        .append(afterMeasure - beforeMeasure)
                        .append("\n");
                out.append(backlog.report());
                out.append(requests(api.requests()));
                out.append("\n").append(subject.report());
                finish(label() + "-rate", out.toString());
            }
        }
    }

    private static void finish(String name, String report) throws IOException {
        System.out.println(report);
        var dir = Path.of("target", "load");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + "-" + Instant.now().toString().replace(':', '-') + ".txt"), report);
    }

    private static String machine() {
        return Runtime.getRuntime().availableProcessors() + " CPUs, load average "
                + ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage() + " at the start, JDK "
                + System.getProperty("java.version")
                + ", the SDK in a JVM of its own and the fakes, broker and publisher in another";
    }

    /** One cold odds change for each of {@code matches} matches, half from each producer, made before the clock starts. */
    private static List<LoadPublisher.Entry> coldBurst(int matches) {
        var base = new String(Corpus.oddsChange(Corpus.TYPICAL), StandardCharsets.UTF_8);
        var burst = new ArrayList<LoadPublisher.Entry>(matches);
        for (int i = 0; i < matches; i++) {
            var message = Fixtures.replace(base, "od:match:198314", "od:match:" + (FIRST_COLD_MATCH + i));
            var fromProducer = i % 2 == 0 ? message : FeedMessages.fromProducer(message, 2, 1);
            burst.add(new LoadPublisher.Entry(fromProducer, FakeFeed.routingKey(fromProducer)));
        }
        return burst;
    }

    private static long requestId(RecordedRequest recovery) {
        var requestId = recovery.parameter("request_id");
        assertThat(requestId).as("request id of " + recovery.path()).isNotBlank();
        return Long.parseLong(requestId);
    }

    /** What the SDK asked of the API, by kind; the rest grouped by their paths with the numbers left out. */
    private static String requests(List<RecordedRequest> requests) {
        long summaries = requests.stream()
                .filter(r -> LoadApi.SUMMARY.matcher(r.path()).matches())
                .count();
        long profiles = requests.stream()
                .filter(r -> LoadApi.PROFILE.matcher(r.path()).matches())
                .count();
        long variants = requests.stream()
                .filter(r -> LoadApi.VARIANT.matcher(r.path()).matches())
                .count();
        long recoveries =
                requests.stream().filter(r -> r.method().equals("POST")).count();
        var others = new TreeMap<String, Integer>();
        for (var request : requests) {
            if (!request.method().equals("POST")
                    && !LoadApi.SUMMARY.matcher(request.path()).matches()
                    && !LoadApi.PROFILE.matcher(request.path()).matches()
                    && !LoadApi.VARIANT.matcher(request.path()).matches()) {
                others.merge(request.path().replaceAll("\\d+", "N"), 1, Integer::sum);
            }
        }
        return "API requests: " + requests.size() + " (summaries " + summaries + ", profiles " + profiles
                + ", variants " + variants + ", recovery requests " + recoveries + ", others "
                + others.values().stream().mapToInt(Integer::intValue).sum() + ")\nothers by path: " + others + "\n";
    }

    /**
     * What the broker holds for the SDK, sampled every two seconds: the messages waiting at the broker
     * and the ones handed out and not yet acknowledged (the prefetch). A message's age is the sum of its
     * wait here and its wait in the SDK, so this says which of the two a lag is.
     */
    private static final class Backlog {
        private final FakeFeed broker;
        private final List<long[]> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean sampling = true;
        private Thread thread;

        Backlog(FakeFeed broker) {
            this.broker = broker;
        }

        void start() {
            thread = Thread.ofPlatform().name("load-backlog").start(() -> {
                try {
                    while (sampling) {
                        samples.add(new long[] {broker.ready(), broker.unacknowledged()});
                        Thread.sleep(2_000);
                    }
                } catch (InterruptedException | RuntimeException stopped) {
                    // the run is over, or the broker is
                }
            });
        }

        void stop() throws InterruptedException {
            sampling = false;
            thread.interrupt();
            thread.join(10_000);
        }

        String report() {
            var ready = samples.stream().mapToLong(sample -> sample[0]).sorted().toArray();
            var unacknowledged =
                    samples.stream().mapToLong(sample -> sample[1]).sorted().toArray();
            if (ready.length == 0) {
                return "broker backlog: not sampled\n";
            }
            return String.format(
                    Locale.ROOT,
                    "broker backlog, %d samples 2 s apart: waiting at the broker median %d, most %d; handed out"
                            + " and unacknowledged median %d, most %d (prefetch 200)%n",
                    ready.length,
                    ready[ready.length / 2],
                    ready[ready.length - 1],
                    unacknowledged[unacknowledged.length / 2],
                    unacknowledged[unacknowledged.length - 1]);
        }
    }

    /** The parent's own view of the restart, in milliseconds from the moment the SDK's JVM said it was starting. */
    private static final class Timeline {
        long start;
        private final List<String> lines = new ArrayList<>();

        void mark(String what) {
            mark(what, System.nanoTime());
        }

        void mark(String what, long at) {
            lines.add(String.format(Locale.ROOT, "%8.0f ms  %s", (at - start) / 1e6, what));
        }

        String report() {
            return "-- the restart as the harness saw it\n" + String.join("\n", lines) + "\n";
        }
    }

    /** The SDK's JVM: started with this JVM's classpath, told what to do over stdin, heard from over stdout. */
    private static final class Subject implements AutoCloseable {
        private final Process process;
        private final Path out;
        private final Map<String, CompletableFuture<Long>> marks = new ConcurrentHashMap<>();

        private Subject(Process process, Path out, Path log) {
            this.process = process;
            this.out = out;
            Thread.ofPlatform().daemon().name("load-subject-output").start(() -> {
                try (var reader = process.inputReader();
                        var writer = new PrintWriter(Files.newBufferedWriter(log))) {
                    for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                        if (line.startsWith("@")) {
                            mark(line.substring(1)).complete(System.nanoTime());
                        } else {
                            writer.println(line);
                        }
                    }
                } catch (IOException ended) {
                    // the process is gone
                }
            });
        }

        private CompletableFuture<Long> mark(String name) {
            return marks.computeIfAbsent(name, _ -> new CompletableFuture<>());
        }

        static Subject start(FakeFeed broker, FakeRestServer api, String name, Map<String, String> options)
                throws IOException {
            var dir = Path.of("target", "load");
            Files.createDirectories(dir);
            var stamp = Instant.now().toString().replace(':', '-');
            var out = dir.resolve(name + "-subject-" + stamp + ".txt");
            var gcLog = dir.resolve(name + "-gc-" + stamp + ".log");
            var trust = trustStore(dir.resolve("fakes.p12"));
            var logging = dir.resolve("logback-warn.xml");
            Files.writeString(logging, """
                    <configuration>
                      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -- %msg%n</pattern></encoder>
                      </appender>
                      <root level="WARN"><appender-ref ref="CONSOLE"/></root>
                    </configuration>
                    """);
            var heap = number("heap", 1024) + "m";
            var command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xms" + heap,
                    "-Xmx" + heap,
                    "-XX:+UseG1GC",
                    "-Xlog:gc:file=" + gcLog + ":uptime",
                    "-Djavax.net.ssl.trustStore=" + trust,
                    "-Djavax.net.ssl.trustStorePassword=changeit",
                    "-Dlogback.configurationFile=" + logging,
                    "-cp",
                    System.getProperty("java.class.path"),
                    LoadSubject.class.getName(),
                    "feedHost=" + broker.host(),
                    "feedPort=" + broker.port(),
                    "apiHost=" + api.apiHost(),
                    "out=" + out,
                    "gcLog=" + gcLog));
            options.forEach((key, value) -> command.add(key + "=" + value));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return new Subject(process, out, dir.resolve(name + "-subject-" + stamp + ".log"));
        }

        /** A trust store holding only the fakes' certificate: all the SDK's JVM needs to trust. */
        private static Path trustStore(Path file) throws IOException {
            try {
                var certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(
                                TestTls.pem().certificate().getBytes(StandardCharsets.UTF_8)));
                var store = KeyStore.getInstance("PKCS12");
                store.load(null, null);
                store.setCertificateEntry("fakes", certificate);
                try (OutputStream stream = Files.newOutputStream(file)) {
                    store.store(stream, "changeit".toCharArray());
                }
                return file.toAbsolutePath();
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("could not make the trust store", e);
            }
        }

        /** The time, by this JVM's clock, the SDK's JVM reported {@code progress}. */
        long await(String progress, Duration within) throws Exception {
            try {
                return mark(progress).get(within.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError(
                        "the SDK's JVM did not report " + progress + " within " + within
                                + (process.isAlive() ? "" : "; it exited with " + process.exitValue()),
                        e);
            }
        }

        void send(String command) throws IOException {
            var stdin = process.getOutputStream();
            stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        /** Ends the run and waits for the report. */
        void stop() throws Exception {
            send("stop");
            await("DONE", Duration.ofMinutes(2));
        }

        String report() throws IOException {
            return Files.readString(out);
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }
}
