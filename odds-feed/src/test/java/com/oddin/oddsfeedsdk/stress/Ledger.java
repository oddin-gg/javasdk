package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Which messages were published and which reached the listener, per match, as sequence numbers.
 *
 * <p>A publisher thread per match stamps the sequence into the odds change's {@code timestamp}
 * (base plus sequence) and publishes it as it is; a message counts as published only once the broker
 * says it routed it. The listener from {@link #listener} reads the sequence back. What may then differ
 * between the two is the lost-message contract of the stress suite:
 * <ul>
 *   <li>{@link #assertExact}: nothing lost, repeated or reordered; for a run without a reconnect.
 *   <li>{@link #assertPrefix}: what arrived is the start of what was published, with no gap; for a
 *       run cut short by a close, which drops what was queued.
 *   <li>{@link #assertGapsOnlyAfterReconnect}: for a run with the connection cut under the
 *       publishers, whatever the dead queue held is gone, so a gap is allowed - but no repeat, no
 *       reorder, and no loss from what was published after the SDK was back.
 * </ul>
 * Choose a base near the present: the SDK judges a producer by the age of its messages.
 */
final class Ledger {

    private static final String MATCH_FIXTURE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String FIXTURE_EVENT = "od:match:198314";

    private final long base;
    private final Map<String, AtomicLong> nextSequence = new ConcurrentHashMap<>();
    /** Per match, how many publishes have returned, whatever the outcome: the first sequence not yet through. */
    private final Map<String, AtomicLong> returned = new ConcurrentHashMap<>();

    private final Map<String, List<Long>> published = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> delivered = new ConcurrentHashMap<>();
    /** Sent, and the broker's answer was an error: it may have been routed, and may arrive. */
    private final Map<String, List<Long>> uncertain = new ConcurrentHashMap<>();

    Ledger(long base) {
        this.base = base;
    }

    /** The odds change of this match stamped with this sequence. */
    String message(String match, long sequence) {
        // a request id makes the SDK take the message for a snapshot's, not for live traffic
        var live = Fixtures.replace(Fixtures.read(MATCH_FIXTURE), " request_id=\"2049987833\"", "");
        var changed = Fixtures.replace(live, FIXTURE_EVENT, match);
        return FeedMessages.stampedAt(changed, base + sequence);
    }

    /**
     * Publishes the next message of this match; call it from one thread per match.
     *
     * @return whether the broker routed it, which is when it counts as published
     */
    boolean publish(FakeFeed broker, String match) {
        return publish(broker::publishAsIs, match);
    }

    /** As {@link #publish(FakeFeed, String)}, to any broker: its answer is whether it routed the message. */
    boolean publish(Predicate<String> broker, String match) {
        var done = returned.computeIfAbsent(match, _ -> new AtomicLong());
        long sequence =
                nextSequence.computeIfAbsent(match, _ -> new AtomicLong()).getAndIncrement();
        try {
            boolean routed;
            try {
                routed = broker.test(message(match, sequence));
            } catch (RuntimeException e) {
                // a broker taking a queue down can route a message to the others and still answer with an
                // error: it is neither published nor known to be lost
                sentWithError(match, sequence);
                throw e;
            }
            if (routed) {
                published(match, sequence);
            }
            return routed;
        } finally {
            // last, so that a mark never counts a message whose broker call is still under way
            done.incrementAndGet();
        }
    }

    void sentWithError(String match, long sequence) {
        record(uncertain, match, sequence);
    }

    void published(String match, long sequence) {
        record(published, match, sequence);
    }

    void delivered(String match, long sequence) {
        record(delivered, match, sequence);
    }

    long publishedCount() {
        return count(published);
    }

    long deliveredCount() {
        return count(delivered);
    }

    /**
     * For each match, the first sequence whose publish had not returned when this was called: a
     * publish still inside the broker call is after the mark, as it may be routed after the connection
     * is back. What a probe's round trip marks.
     */
    Map<String, Long> mark() {
        var marks = new HashMap<String, Long>();
        returned.forEach((match, done) -> marks.put(match, done.get()));
        return marks;
    }

    /** How many messages of every match were published at or after the {@link #mark}. */
    long publishedSince(Map<String, Long> mark) {
        return since(published, mark);
    }

    /** How many messages of every match were sent with an error at or after the {@link #mark}: each may have arrived. */
    long uncertainSince(Map<String, Long> mark) {
        return since(uncertain, mark);
    }

    /** How many messages of every match arrived that were published at or after the {@link #mark}. */
    long deliveredSince(Map<String, Long> mark) {
        return since(delivered, mark);
    }

    private static long since(Map<String, List<Long>> in, Map<String, Long> mark) {
        long count = 0;
        for (var match : in.keySet()) {
            long from = mark.getOrDefault(match, 0L);
            count += copy(in, match).stream()
                    .filter(sequence -> sequence >= from)
                    .count();
        }
        return count;
    }

    /** Waits until as many messages arrived as were published, polling; false if it takes longer. */
    boolean awaitCaughtUp(Duration limit) throws InterruptedException {
        long until = System.nanoTime() + limit.toNanos();
        while (deliveredCount() < publishedCount() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        return deliveredCount() >= publishedCount();
    }

    /** Records an odds change's sequence per match; every other message is ignored. */
    OddsFeedListener listener() {
        return new Recorder();
    }

    /** Per match, what arrived is what was published, in order. */
    void assertExact() {
        assertSomethingWasPublished();
        for (var match : matches()) {
            assertThat(delivered(match))
                    .as(
                            "%s: delivered against published (%d)",
                            match, published(match).size())
                    .containsExactlyElementsOf(published(match));
        }
    }

    /**
     * Per match, what arrived is the first part of what was published, with no gap, repeat or
     * reorder. A message sent with an error may have been routed all the same, so it is neither
     * required nor refused: of the published and the uncertain messages together, in order, what
     * arrived must be increasing without repeats, hold only those, and hold every published one
     * below the last that arrived. An uncertain message may be among them or missing; a published
     * one that is missing while a later one arrived is a gap. Unlike the other checks it accepts a
     * ledger that published nothing, for a close that came before any publish; the caller counts
     * what arrived over all its runs.
     */
    void assertPrefix() {
        // no guard against an empty ledger: a close that wins its race with open() leaves nothing published
        for (var match : matches()) {
            var arrived = delivered(match);
            if (arrived.isEmpty()) {
                // nothing arrived, which is the shortest start there is
                continue;
            }
            var sent = published(match);
            assertThat(arrived).as("%s: delivered", match).isSorted().doesNotHaveDuplicates();
            var possible = new ArrayList<>(sent);
            possible.addAll(copy(uncertain, match));
            assertThat(possible)
                    .as("%s: published, or sent with an error, which holds all that arrived", match)
                    .containsAll(arrived);
            long last = arrived.getLast();
            assertThat(arrived)
                    .as("%s: delivered, which must hold every published message up to %d", match, last)
                    .containsAll(
                            sent.stream().filter(sequence -> sequence < last).toList());
        }
    }

    /**
     * Per match: only published messages arrived, in increasing order and without repeats, and every
     * message published at or after the {@link #mark} arrived. A message whose publishing failed with
     * an error may have arrived, or not.
     */
    void assertGapsOnlyAfterReconnect(Map<String, Long> mark) {
        assertSomethingWasPublished();
        for (var match : matches()) {
            var arrived = delivered(match);
            var sent = published(match);
            assertThat(arrived).as("%s: delivered", match).isSorted().doesNotHaveDuplicates();
            var possible = new ArrayList<>(sent);
            possible.addAll(copy(uncertain, match));
            assertThat(possible)
                    .as("%s: published, or sent with an error, which holds all that arrived", match)
                    .containsAll(arrived);
            var afterMark = sent.stream()
                    .filter(sequence -> sequence >= mark.getOrDefault(match, 0L))
                    .toList();
            assertThat(arrived)
                    .as("%s: delivered, which must hold all published since the reconnect", match)
                    .containsAll(afterMark);
        }
    }

    /** A run that published nothing proves nothing: the listener may never have been wired. */
    private void assertSomethingWasPublished() {
        assertThat(publishedCount())
                .as("messages published; the checks of an empty ledger prove nothing")
                .isPositive();
    }

    private List<String> matches() {
        var all = new TreeMap<String, Boolean>();
        published.keySet().forEach(match -> all.put(match, true));
        delivered.keySet().forEach(match -> all.put(match, true));
        return List.copyOf(all.keySet());
    }

    private List<Long> published(String match) {
        return copy(published, match);
    }

    private List<Long> delivered(String match) {
        return copy(delivered, match);
    }

    private static List<Long> copy(Map<String, List<Long>> in, String match) {
        var list = in.get(match);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    private static void record(Map<String, List<Long>> into, String match, long sequence) {
        into.computeIfAbsent(match, _ -> Collections.synchronizedList(new ArrayList<>()))
                .add(sequence);
    }

    private static long count(Map<String, List<Long>> in) {
        return in.values().stream().mapToLong(List::size).sum();
    }

    private final class Recorder implements OddsFeedListener {
        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            delivered(
                    message.getEvent().getId().toString(),
                    message.getTimestamp().getCreated() - base);
        }

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
}
