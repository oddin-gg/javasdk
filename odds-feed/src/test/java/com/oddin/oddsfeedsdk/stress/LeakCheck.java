package com.oddin.oddsfeedsdk.stress;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a closed feed must leave behind: nothing. Taken before the feed is built, it remembers the
 * threads then alive; {@link #assertClean} waits for the feed's own - named {@code oddsfeed*} or
 * {@code AMQP*} - to end and for the broker to show no connection and no client queue, and then for
 * the REST fake to see no request in flight. The same checks {@code OddsFeedOpenTest} makes, for a
 * stress run to make after each of its cycles.
 *
 * <p>Threads are read from a JSON thread dump, which, unlike {@code Thread.getAllStackTraces()},
 * lists virtual threads too. A virtual thread counts only if it has a name that starts as the feed's
 * do; the feed's unnamed ones - those of its per-task executors - cannot be told from anyone else's
 * and are not seen.
 */
final class LeakCheck {

    private static final Pattern THREAD = Pattern.compile(
            "\"tid\": \"(\\d+)\",\\s*(?:\"time\": \"[^\"]*\",\\s*)?(?:\"virtual\": true,\\s*)?\"name\": \"([^\"]*)\"");

    private final Set<Long> before;
    private FakeFeed broker;
    private FakeRestServer api;

    private LeakCheck(Set<Long> before) {
        this.before = before;
    }

    /** Remembers the threads that exist now. */
    static LeakCheck begin() {
        return new LeakCheck(new HashSet<>(threads().keySet()));
    }

    /** Also checks this broker's connections and client queues. */
    LeakCheck broker(FakeFeed broker) {
        this.broker = broker;
        return this;
    }

    /** Also checks that this fake has no request in flight. */
    LeakCheck rest(FakeRestServer api) {
        this.api = api;
        return this;
    }

    /** The feed threads, platform or virtual, that started since {@link #begin} and are still alive. */
    Set<String> leakedThreads() {
        var leaked = new HashSet<String>();
        threads().forEach((id, name) -> {
            if (!before.contains(id) && (name.startsWith("oddsfeed") || name.startsWith("AMQP"))) {
                leaked.add(name);
            }
        });
        return leaked;
    }

    /** Every live thread, platform or virtual, as id and name, from a thread dump. */
    private static Map<Long, String> threads() {
        try {
            var directory = Files.createTempDirectory("leakcheck");
            var file = directory.resolve("threads.json");
            try {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                        .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
                var threads = new LinkedHashMap<Long, String>();
                var found = THREAD.matcher(Files.readString(file));
                while (found.find()) {
                    threads.put(Long.parseLong(found.group(1)), found.group(2));
                }
                return threads;
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not take a thread dump", e);
        }
    }

    /** Fails with everything left once it has had this long to go away. */
    void assertClean(Duration wait) throws InterruptedException {
        long until = System.nanoTime() + wait.toNanos();
        var leaks = leaks();
        while (!leaks.isEmpty() && System.nanoTime() < until) {
            Thread.sleep(50);
            leaks = leaks();
        }
        if (leaks.isEmpty() && api != null) {
            try {
                api.awaitQuiet();
            } catch (IllegalStateException e) {
                leaks.add("REST requests still arriving: " + e.getMessage());
            }
        }
        if (!leaks.isEmpty()) {
            throw new AssertionError("left behind after " + wait + ": " + leaks);
        }
    }

    private List<String> leaks() {
        var leaks = new ArrayList<String>();
        var threads = leakedThreads();
        if (!threads.isEmpty()) {
            leaks.add("threads " + threads);
        }
        if (broker != null) {
            var connections = broker.openConnections();
            if (!connections.isEmpty()) {
                leaks.add("broker connections " + connections);
            }
            var queues = broker.clientQueues();
            if (!queues.isEmpty()) {
                leaks.add("client queues " + queues);
            }
        }
        return leaks;
    }
}
