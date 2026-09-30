package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** The connection events a transport reported, for a test to wait for. */
final class Recorded implements ConnectionEvents {

    final List<String> events = new CopyOnWriteArrayList<>();
    final List<RawDelivery> alives = new CopyOnWriteArrayList<>();
    final List<String> reasons = new CopyOnWriteArrayList<>();
    final List<Long> waits = new CopyOnWriteArrayList<>();

    @Override
    public void connecting() {
        events.add("connecting");
    }

    @Override
    public void up() {
        events.add("up");
    }

    @Override
    public void down(String reason) {
        events.add("down");
    }

    @Override
    public void recovering(int attempt, long waitMillis, String reason) {
        events.add("recovering");
        reasons.add(reason);
        waits.add(waitMillis);
    }

    @Override
    public void fatal(String reason, @Nullable Throwable cause) {
        events.add("fatal: " + reason);
    }

    void alive(RawDelivery delivery) {
        alives.add(delivery);
    }

    /** Waits until an event matches, for up to {@code limit}. */
    void await(Predicate<String> event, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (events.stream().noneMatch(event)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no such event within " + limit + "; got " + events);
            }
            Thread.sleep(20);
        }
    }

    long count(String event) {
        return events.stream().filter(event::equals).count();
    }
}
