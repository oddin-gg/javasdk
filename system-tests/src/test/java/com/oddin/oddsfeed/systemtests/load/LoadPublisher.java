package com.oddin.oddsfeed.systemtests.load;

import com.oddin.oddsfeed.benchmarks.AmqpPublisher;
import com.oddin.oddsfeed.benchmarks.Corpus;
import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.TestTls;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.random.RandomGeneratorFactory;

/**
 * Publishes odds changes to the fake feed's broker over AMQP at a steady rate from several threads, each
 * with a connection of its own and taking every n-th message of the schedule, plus an alive per
 * producer every few seconds, as the producers send them. (The fake feed's own publisher, one HTTP
 * request a message, stops at about 200 odds changes a second.) The messages come from a pool made before the clock starts, of the corpus's odds changes
 * in the mix of sizes asked for.
 */
final class LoadPublisher implements AutoCloseable {

    /** A message is late when it goes out this long after its turn. */
    private static final long LATE_NANOS = Duration.ofMillis(100).toNanos();

    private static final long ALIVE_EVERY_MILLIS = 5_000;

    private final FakeFeed feed;
    private final List<Entry> pool;
    private final int rate;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong late = new AtomicLong();
    private final AtomicLong mostLate = new AtomicLong();
    private final AtomicLongArray perSecond;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;
    private volatile Throwable failure;
    private long begin;
    private Thread alive;

    /**
     * @param pool the messages, taken in turn
     * @param rate odds changes a second, across the threads
     * @param seconds the longest the publisher is meant to run, for its per-second counts
     */
    LoadPublisher(FakeFeed feed, List<Entry> pool, int rate, int seconds) {
        this.feed = feed;
        this.pool = pool;
        this.rate = rate;
        this.perSecond = new AtomicLongArray(seconds + 60);
    }

    /**
     * The pool: {@code count} odds changes of the sizes in {@code mix} ({@code "20:70,150:28,500:2"}:
     * markets, then weight) for {@code matches} matches, {@code liveShare} per cent from the live
     * producer and the rest from the prematch one. The same pool for the same arguments.
     */
    static List<Entry> pool(String mix, int count, int matches, int liveShare) {
        var sizes = new ArrayList<Integer>();
        var weights = new ArrayList<Integer>();
        int total = 0;
        for (var part : mix.split(",", -1)) {
            var pair = part.split(":", -1);
            sizes.add(Integer.parseInt(pair[0].strip()));
            weights.add(Integer.parseInt(pair[1].strip()));
            total += weights.getLast();
        }
        var bases = new ArrayList<String>();
        for (int size : sizes) {
            bases.add(new String(Corpus.oddsChange(size), java.nio.charset.StandardCharsets.UTF_8));
        }
        var random = RandomGeneratorFactory.of("L64X128MixRandom").create(count);
        var pool = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            int pick = random.nextInt(total);
            int which = 0;
            while (pick >= weights.get(which)) {
                pick -= weights.get(which++);
            }
            var message =
                    Fixtures.replace(bases.get(which), "od:match:198314", "od:match:" + (6_000_000 + i % matches));
            var fromProducer = random.nextInt(100) < liveShare ? message : FeedMessages.fromProducer(message, 2, 1);
            pool.add(new Entry(fromProducer, FakeFeed.routingKey(fromProducer)));
        }
        return pool;
    }

    /** What the sizes in the mix average, in bytes: how much of the broker's and the SDK's work one message is. */
    static long averageBytes(List<Entry> pool) {
        return pool.stream().mapToLong(entry -> entry.message().length()).sum() / pool.size();
    }

    /** A message and the routing key the feed sends it with. */
    record Entry(String message, String routingKey) {}

    /** Publishes these messages, stamped now, over one connection, as fast as it goes; for a restart's snapshot. */
    static void burst(FakeFeed feed, List<Entry> messages) throws IOException, TimeoutException {
        try (var publisher = open(feed)) {
            for (var entry : messages) {
                publisher.publish(entry.routingKey(), stamp(entry.message()));
            }
        }
    }

    private static AmqpPublisher open(FakeFeed feed) throws IOException, TimeoutException {
        return new AmqpPublisher(
                feed.host(), feed.port(), feed.virtualHost(), FakeFeed.EXCHANGE, TestTls.clientContext());
    }

    /** The message as the producers' clock, this machine's, makes it. */
    private static String stamp(String message) {
        return FeedMessages.stampedAt(message, System.currentTimeMillis());
    }

    void start(int publishers) {
        begin = System.nanoTime();
        for (int k = 0; k < publishers; k++) {
            int first = k;
            threads.add(Thread.ofPlatform().name("load-publisher-" + k).start(() -> publish(first, publishers)));
        }
        alive = Thread.ofPlatform().name("load-alive").start(this::alives);
        threads.add(alive);
    }

    private void publish(int first, int step) {
        long interval = 1_000_000_000L / rate;
        try (var publisher = open(feed)) {
            for (long index = first; running; index += step) {
                long due = begin + index * interval;
                long now = System.nanoTime();
                while (now < due && running) {
                    LockSupport.parkNanos(due - now);
                    now = System.nanoTime();
                }
                if (!running) {
                    return;
                }
                if (now - due > LATE_NANOS) {
                    late.incrementAndGet();
                }
                mostLate.accumulateAndGet(now - due, Math::max);
                var entry = pool.get((int) (index % pool.size()));
                publisher.publish(entry.routingKey(), stamp(entry.message()));
                published.incrementAndGet();
                int second = (int) ((System.nanoTime() - begin) / 1_000_000_000L);
                if (second < perSecond.length()) {
                    perSecond.incrementAndGet(second);
                }
            }
        } catch (IOException | TimeoutException | RuntimeException | Error e) {
            // a publish the close cut off is not a failure
            if (running) {
                failure = e;
                running = false;
            }
        }
    }

    private void alives() {
        try (var publisher = open(feed)) {
            var first = FeedMessages.alive(1, true);
            var second = FeedMessages.alive(2, true);
            var firstKey = FakeFeed.routingKey(first);
            var secondKey = FakeFeed.routingKey(second);
            while (running) {
                publisher.publish(firstKey, stamp(first));
                publisher.publish(secondKey, stamp(second));
                Thread.sleep(ALIVE_EVERY_MILLIS);
            }
        } catch (InterruptedException stopped) {
            // closed
        } catch (IOException | TimeoutException | RuntimeException | Error e) {
            if (running) {
                failure = e;
                running = false;
            }
        }
    }

    long published() {
        return published.get();
    }

    /** Stops publishing and waits for the threads; fails with what a publishing thread failed with. */
    @Override
    public void close() {
        running = false;
        if (alive != null) {
            alive.interrupt();
        }
        for (var thread : threads) {
            try {
                thread.join(10_000);
                thread.interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw new IllegalStateException("a publishing thread failed", failure);
        }
    }

    /** How the publishing went: what was asked for against what went out. */
    String report() {
        long seconds = Math.max(1, (System.nanoTime() - begin) / 1_000_000_000L);
        var counts = new long[(int) Math.min(seconds, perSecond.length())];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = perSecond.get(i);
        }
        java.util.Arrays.sort(counts);
        // the last second is partial
        long median = counts.length == 0 ? 0 : counts[counts.length / 2];
        long lowest = counts.length == 0 ? 0 : counts[Math.min(1, counts.length - 1)];
        return String.format(
                Locale.ROOT,
                "publisher: asked %d/s, published %d in %d s (%.0f/s; seconds' median %d, lowest %d), "
                        + "%d went out over 100 ms late (the most %d ms), pool of %d, %d bytes on average",
                rate,
                published.get(),
                seconds,
                published.get() / (double) seconds,
                median,
                lowest,
                late.get(),
                mostLate.get() / 1_000_000,
                pool.size(),
                averageBytes(pool));
    }
}
