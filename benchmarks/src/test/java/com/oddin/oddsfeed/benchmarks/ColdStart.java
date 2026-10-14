package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
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
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A restart as the SDK sees it: a feed opens against the fake API, which answers every request after
 * a fixed delay with generated, production-sized catalogs, and odds changes for matches nothing is
 * cached for arrive at once. {@link #run} times how long the feed takes to be caught up.
 *
 * <p>The fake API's hook only reads the request and returns a reply, so it is safe on the request
 * threads; the catalogs and the per-match answers come from {@link Catalogs}, by path.
 */
final class ColdStart {

    static final Locale EN = Locale.ENGLISH;

    /** The first match id; the matches that follow are numbered on from it. */
    private static final long FIRST_MATCH = 5_000_000;

    private static final Duration WAIT = Duration.ofMinutes(2);

    private static final Pattern SUMMARY = Pattern.compile("/v1/sports/en/sport_events/od:match:(\\d+)/summary");
    private static final Pattern PROFILE = Pattern.compile("/v1/sports/en/competitors/od:competitor:(\\d+)/profile");
    private static final Pattern VARIANT = Pattern.compile("/v1/descriptions/en/markets/(\\d+)/variants/([^/]+)");
    private static final Pattern ID = Pattern.compile("od:[a-z]+:\\d+");

    static final String SUMMARIES = "GET /v1/sports/en/sport_events/{id}/summary";
    static final String PROFILES = "GET /v1/sports/en/competitors/{id}/profile";
    static final String VARIANTS = "GET /v1/descriptions/en/markets/{id}/variants/{variant}";

    /** What the callbacks do with the match of a message. */
    enum Reads {
        /** Nothing: only the preload, if on, loads the match. */
        NOTHING,
        /** Its name in English, as a client showing the match does. */
        NAME,
        /**
         * Its name, and the name in English of every market of the message, as a client showing the
         * odds does: each variant market's description is fetched, the first time, on the callback's thread.
         */
        NAME_AND_MARKETS
    }

    /**
     * @param matches the odds changes sent, one for each of this many matches nothing is cached for
     * @param latency what every REST request is answered after
     * @param eager whether the eager entity preload is on
     * @param reads what the callbacks read
     * @param variantsPerMatch the variant markets, each with a variant of its own, that every odds
     *     change carries after the corpus's markets; none unless the callbacks read the markets' names
     */
    record Settings(int matches, Duration latency, boolean eager, Reads reads, int variantsPerMatch) {

        /** An odds change with no variant markets, as the scenario has always sent. */
        Settings(int matches, Duration latency, boolean eager, Reads reads) {
            this(matches, latency, eager, reads, 0);
        }

        /** Summaries the feed must ask for: one per match, whoever loads it. */
        int summaries() {
            return eager || reads != Reads.NOTHING ? matches : 0;
        }

        /** Variant descriptions: one for each variant market of each message, which the callbacks read once. */
        int variants() {
            return reads == Reads.NAME_AND_MARKETS ? matches * variantsPerMatch : 0;
        }

        /** Whether the odds changes are the variant mode's: variant markets after the corpus's, perhaps none. */
        boolean sendsVariants() {
            return variantsPerMatch > 0 || reads == Reads.NAME_AND_MARKETS;
        }

        /**
         * How long the burst may take before the scenario gives up: {@link #WAIT}, and the variants,
         * which the callbacks read one by one, each a latency, in case no two are fetched at once.
         */
        Duration bound() {
            return WAIT.plus(latency.multipliedBy(variants()));
        }

        /** Competitor profiles: a summary's load warms its competitors, whoever asked for the summary. */
        int profiles() {
            return summaries() * Catalogs.COMPETITORS_PER_MATCH;
        }

        Settings with(Duration newLatency) {
            return new Settings(matches, newLatency, eager, reads, variantsPerMatch);
        }
    }

    /**
     * @param caughtUp first publish until every callback ran and the requests they caused were answered
     * @param afterLastPublish the same, counted from the last publish
     * @param requests the requests the feed made, by method and path with the ids left out
     * @param repeated paths asked for more than once, with their counts
     * @param mostInFlight the most requests the fake API was answering at once
     * @param longestCallback the longest an odds change's callback took, in the session's thread
     * @param longestRead the longest a single market name took to read in a callback; none when the
     *     callbacks read no market
     * @param healthEvents the changes of the feed's health the SDK told, as {@code component: previous to now}
     * @param health the feed's state when it was caught up
     */
    record Result(
            Duration caughtUp,
            Duration afterLastPublish,
            Map<String, Integer> requests,
            Map<String, Integer> repeated,
            int mostInFlight,
            Duration longestCallback,
            Duration longestRead,
            List<String> healthEvents,
            HealthState health) {

        int count(String key) {
            return requests.getOrDefault(key, 0);
        }

        int total() {
            return requests.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** A feed open and its fake API; closes both. */
    static final class Running implements AutoCloseable {
        final FakeRestServer api;
        private final Arrivals arrivals = new Arrivals();
        private final Quiet events = new Quiet();
        private @Nullable OddsFeed feed;

        /** Takes over {@code api}: it is closed with the feed, or here when the feed does not come up. */
        Running(FakeRestServer api, Settings settings, Listener listener, FakeFeed broker, Duration bound)
                throws InterruptedException {
            this.api = api;
            try {
                api.respondWith(hook(settings.latency(), arrivals));
                var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment(broker.host(), api.apiHost(), broker.port())
                        .setMessagingSslContext(TestTls.clientContext())
                        .setAccessToken("cold-start")
                        .setEagerEntityPreload(settings.eager())
                        // without a preload locale no catalog is loaded at open: a client sets them
                        .setPreloadLocales(List.of(EN))
                        .build();
                feed = new OddsFeed(events, configuration);
                feed.getSessionBuilder()
                        .setListener(listener)
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                awaitBound(broker, bound);
            } catch (Throwable e) {
                // the caller never gets a reference to close: a feed left open would send late
                // requests into the next test's fake
                try {
                    if (feed != null) {
                        feed.close();
                    }
                } catch (Throwable suppressed) {
                    e.addSuppressed(suppressed);
                } finally {
                    try {
                        api.close();
                    } catch (Throwable suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw e;
            }
        }

        /** Sends a fixture change, which the preload skips, until the broker routes it: the session's queue is bound. */
        private static void awaitBound(FakeFeed broker, Duration bound) throws InterruptedException {
            var probe = Fixtures.read("feed/fixture_change/fixture_change.xml");
            long until = System.nanoTime() + bound.toNanos();
            while (!broker.publish(probe)) {
                if (System.nanoTime() > until) {
                    throw new AssertionError("the session's queue was not bound within " + bound);
                }
                Thread.sleep(50);
            }
        }

        FeedHealth health() {
            return requireNonNull(feed).getHealth();
        }

        @Override
        public void close() {
            try {
                api.awaitQuiet();
            } finally {
                try {
                    requireNonNull(feed).close();
                } finally {
                    api.close();
                }
            }
        }
    }

    private ColdStart() {}

    /** Opens a feed and times the burst of cold odds changes until it is caught up. */
    static Result run(FakeFeed broker, Settings settings) throws InterruptedException {
        var listener = new Listener(settings.reads(), settings.matches());
        try (var running = new Running(FakeRestServer.start(), settings, listener, broker, WAIT)) {
            var corpus = new String(Corpus.oddsChange(Corpus.TYPICAL), StandardCharsets.UTF_8);
            // built before the clock starts: only the broker's own publish cost is in the time
            var messages = new String[settings.matches()];
            for (int i = 0; i < messages.length; i++) {
                var odds = settings.sendsVariants()
                        ? new String(
                                Corpus.oddsChange(Corpus.TYPICAL, settings.variantsPerMatch(), FIRST_MATCH + i),
                                StandardCharsets.UTF_8)
                        : corpus;
                messages[i] = Fixtures.replace(odds, "od:match:198314", "od:match:" + (FIRST_MATCH + i));
            }
            long started = System.nanoTime();
            long lastPublished = started;
            for (var message : messages) {
                if (!broker.publish(message)) {
                    throw new AssertionError("an odds change was not routed to the session");
                }
                lastPublished = System.nanoTime();
            }
            if (!listener.done.await(settings.bound().toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("only " + (settings.matches() - listener.done.getCount())
                        + " callbacks ran within " + settings.bound());
            }
            listener.check();
            awaitRequests(running, settings);
            long end = Math.max(listener.lastDone.get(), running.arrivals.lastAnswered(settings.latency()));
            running.api.awaitQuiet();
            var requests = count(running.api);
            return new Result(
                    Duration.ofNanos(end - started),
                    Duration.ofNanos(end - lastPublished),
                    requests.counts(),
                    requests.repeated(),
                    running.api.mostInFlight(),
                    Duration.ofNanos(listener.longestCallback.get()),
                    Duration.ofNanos(listener.longestRead.get()),
                    List.copyOf(running.events.healthEvents),
                    running.health().state());
        }
    }

    /** One cold odds change on a fresh feed: until its callback has read the match's name and the first market's. */
    static Duration firstMessage(FakeFeed broker, Settings settings) throws InterruptedException {
        var listener = new Listener(Reads.NAME, 1);
        listener.marketToo = true;
        try (var running = new Running(FakeRestServer.start(), settings, listener, broker, WAIT)) {
            var message = Fixtures.replace(
                    new String(Corpus.oddsChange(Corpus.TYPICAL), StandardCharsets.UTF_8),
                    "od:match:198314",
                    "od:match:" + FIRST_MATCH);
            long started = System.nanoTime();
            if (!broker.publish(message)) {
                throw new AssertionError("the odds change was not routed to the session");
            }
            if (!listener.done.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("the callback did not run within " + WAIT);
            }
            listener.check();
            running.api.awaitQuiet();
            return Duration.ofNanos(listener.lastDone.get() - started);
        }
    }

    /** Waits until the requests the settings call for have all arrived, and been answered. */
    private static void awaitRequests(Running running, Settings settings) throws InterruptedException {
        long until = System.nanoTime() + WAIT.toNanos();
        while (running.arrivals.matching(SUMMARY) < settings.summaries()
                || running.arrivals.matching(PROFILE) < settings.profiles()
                || running.arrivals.matching(VARIANT) < settings.variants()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("the requests did not arrive within " + WAIT + ": summaries "
                        + running.arrivals.matching(SUMMARY) + " of " + settings.summaries() + ", profiles "
                        + running.arrivals.matching(PROFILE) + " of " + settings.profiles() + ", variants "
                        + running.arrivals.matching(VARIANT) + " of " + settings.variants());
            }
            Thread.sleep(5);
        }
    }

    private record Counted(Map<String, Integer> counts, Map<String, Integer> repeated) {}

    private static Counted count(FakeRestServer api) {
        var counts = new TreeMap<String, Integer>();
        var exact = new HashMap<String, Integer>();
        for (var request : api.requests()) {
            // a variant's id is not an urn: the variants are one endpoint, whatever market and variant
            var path =
                    VARIANT.matcher(request.path()).replaceAll("/v1/descriptions/en/markets/{id}/variants/{variant}");
            counts.merge(request.method() + " " + ID.matcher(path).replaceAll("{id}"), 1, Integer::sum);
            exact.merge(request.method() + " " + request.path(), 1, Integer::sum);
        }
        var repeated = new TreeMap<String, Integer>();
        exact.forEach((path, n) -> {
            if (n > 1) {
                repeated.put(path, n);
            }
        });
        return new Counted(counts, repeated);
    }

    /**
     * The fake API's answers: the generated catalogs and the per-match answers by path, each after the
     * latency; what a catalog does not generate is the fixtures', delayed the same.
     */
    private static java.util.function.Function<RecordedRequest, @Nullable Reply> hook(
            Duration latency, Arrivals arrivals) {
        var markets = text(Catalogs.marketDescriptions(Catalogs.MARKET_DESCRIPTIONS));
        var sports = text(Catalogs.sports(Catalogs.SPORTS));
        var fixed = Map.of(
                "/v1/users/whoami", Fixtures.read("rest/whoami/bookmaker_details.xml"),
                "/v1/descriptions/producers", Fixtures.read("rest/producers/producers.xml"),
                "/v1/descriptions/void_reasons", Fixtures.read("rest/void_reasons/void_reasons.xml"),
                "/v1/descriptions/en/match_status", Fixtures.read("rest/match_status/match_status_descriptions.xml"),
                "/v1/descriptions/en/markets", markets,
                "/v1/sports/en/sports", sports);
        return request -> {
            if (!request.method().equals("GET")) {
                return null; // the recovery requests: the fake's 202
            }
            var path = request.path();
            arrivals.arrived(path);
            String body = fixed.get(path);
            if (body == null) {
                var summary = SUMMARY.matcher(path);
                var profile = PROFILE.matcher(path);
                var variant = VARIANT.matcher(path);
                if (summary.matches()) {
                    body = text(Catalogs.matchSummary(Long.parseLong(summary.group(1))));
                } else if (profile.matches()) {
                    body = text(Catalogs.competitorProfile(Long.parseLong(profile.group(1))));
                } else if (variant.matches()) {
                    body = text(Catalogs.variantDescription(
                            Integer.parseInt(variant.group(1)),
                            URLDecoder.decode(variant.group(2), StandardCharsets.UTF_8)));
                } else {
                    return null; // not something the scenario expects: the fixtures', undelayed
                }
            }
            return Reply.of(200, body).after(latency);
        };
    }

    private static String text(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8);
    }

    /** When the requests arrived; one answered a latency after its arrival. */
    private static final class Arrivals {
        private final AtomicLong last = new AtomicLong();
        private final Map<Pattern, AtomicInteger> perPattern = new ConcurrentHashMap<>();

        void arrived(String path) {
            last.accumulateAndGet(System.nanoTime(), Math::max);
            for (var pattern : List.of(SUMMARY, PROFILE, VARIANT)) {
                if (pattern.matcher(path).matches()) {
                    perPattern
                            .computeIfAbsent(pattern, _ -> new AtomicInteger())
                            .incrementAndGet();
                }
            }
        }

        int matching(Pattern pattern) {
            var n = perPattern.get(pattern);
            return n == null ? 0 : n.get();
        }

        /** The last arrival plus the delay it was held for: when the last answer left. */
        long lastAnswered(Duration latency) {
            return last.get() + latency.toNanos();
        }
    }

    /** The session's listener: counts down per odds change, after reading what the settings say. */
    static final class Listener implements OddsFeedListener {
        final CountDownLatch done;
        final AtomicLong lastDone = new AtomicLong();
        /** The longest a callback took, and the longest one market name read in one, in nanoseconds. */
        final AtomicLong longestCallback = new AtomicLong();

        final AtomicLong longestRead = new AtomicLong();
        private final Reads reads;
        volatile boolean marketToo;
        private final java.util.concurrent.atomic.AtomicReference<@Nullable Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        Listener(Reads reads, int expected) {
            this.reads = reads;
            this.done = new CountDownLatch(expected);
        }

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            long entered = System.nanoTime();
            try {
                if (reads != Reads.NOTHING) {
                    var name = requireNonNull(message.getEvent().getName(EN));
                    if (!name.startsWith("Match ")) {
                        throw new AssertionError("an unexpected match name: " + name);
                    }
                }
                if (reads == Reads.NAME_AND_MARKETS) {
                    for (var market : message.getMarkets()) {
                        long read = System.nanoTime();
                        var name = requireNonNull(market.getName(EN));
                        longestRead.accumulateAndGet(System.nanoTime() - read, Math::max);
                        if (!name.startsWith("Market ")) {
                            throw new AssertionError("an unexpected market name: " + name);
                        }
                    }
                }
                if (marketToo) {
                    // the first message is the plain one: a variant market is read from a request of its own, which
                    // the variant mode measures
                    var plain = message.getMarkets().stream()
                            .filter(market -> !market.getSpecifiers().containsKey("variant"))
                            .findFirst()
                            .orElseThrow();
                    requireNonNull(plain.getName(EN));
                }
            } catch (RuntimeException | AssertionError e) {
                failure.compareAndSet(null, e);
            }
            long left = System.nanoTime();
            longestCallback.accumulateAndGet(left - entered, Math::max);
            lastDone.accumulateAndGet(left, Math::max);
            done.countDown();
        }

        /** Fails with what a callback threw, if it did. */
        void check() {
            var thrown = failure.get();
            if (thrown != null) {
                throw new AssertionError("a callback failed", thrown);
            }
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

    static final class Quiet implements GlobalEventsListener {
        final List<String> healthEvents = new CopyOnWriteArrayList<>();

        @Override
        public void onHealthEvent(HealthEvent event) {
            healthEvents.add(
                    event.component() + ": " + event.previous() + " to " + event.state() + ", " + event.reason());
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
