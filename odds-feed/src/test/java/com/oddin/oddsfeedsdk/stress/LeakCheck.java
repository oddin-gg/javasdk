package com.oddin.oddsfeedsdk.stress;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a closed feed must leave behind: nothing. Taken before the feed is built, it remembers the
 * threads then alive; {@link #assertClean} waits for the feed's own - named {@code oddsfeed*} or
 * {@code AMQP*} - to end and for the broker to show no connection and no client queue, and then for
 * the REST fake to see no request in flight. The same checks {@code OddsFeedOpenTest} makes, for a
 * stress run to make after each of its cycles.
 */
final class LeakCheck {

    private final Set<Thread> before;
    private FakeFeed broker;
    private FakeRestServer api;

    private LeakCheck(Set<Thread> before) {
        this.before = before;
    }

    /** Remembers the threads that exist now. */
    static LeakCheck begin() {
        return new LeakCheck(Thread.getAllStackTraces().keySet());
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

    /** The feed threads that started since {@link #begin} and are still alive. */
    Set<String> leakedThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.isAlive())
                .map(Thread::getName)
                .filter(name -> name.startsWith("oddsfeed") || name.startsWith("AMQP"))
                .collect(Collectors.toSet());
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
