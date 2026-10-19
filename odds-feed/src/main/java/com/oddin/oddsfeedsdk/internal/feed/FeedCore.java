package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.cache.LiveState.Takeover;
import com.oddin.oddsfeedsdk.internal.catalog.CatalogHealth;
import com.oddin.oddsfeedsdk.internal.catalog.MarketDescriptions;
import com.oddin.oddsfeedsdk.internal.catalog.MatchStatusDescriptions;
import com.oddin.oddsfeedsdk.internal.catalog.VoidReasons;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.oddin.oddsfeedsdk.internal.descriptions.DescriptionManager;
import com.oddin.oddsfeedsdk.internal.descriptions.StatusDescriptions;
import com.oddin.oddsfeedsdk.internal.dispatch.ClockOffsets;
import com.oddin.oddsfeedsdk.internal.dispatch.FixtureChanges;
import com.oddin.oddsfeedsdk.internal.entity.CacheSizes;
import com.oddin.oddsfeedsdk.internal.entity.Entities;
import com.oddin.oddsfeedsdk.internal.entity.MatchCaches;
import com.oddin.oddsfeedsdk.internal.entity.ProfileCaches;
import com.oddin.oddsfeedsdk.internal.entity.SportsInfo;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.loader.SideLoads;
import com.oddin.oddsfeedsdk.internal.message.MessageFactory;
import com.oddin.oddsfeedsdk.internal.producer.Bookmaker;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.replay.Replay;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.internal.rest.Startup;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a feed is made of before it opens: who the bookmaker is, the producers, the REST client, the
 * caches and catalogs, the managers over them and the message factory, reporting to the feed's
 * events dispatcher, started first, so the client hears of every API call as it is made, the
 * start's own included, as the Go SDK tells them. One per {@code OddsFeed}, built on the first call
 * that needs it and closed with the feed.
 *
 * @param fetches where the loads, the fan-outs and the recovery requests run: virtual threads
 * @param events the client's events, delivered from the start on: the feed's, not closed with this
 */
public record FeedCore(
        ApiClient api,
        EventsDispatcher events,
        Bookmaker bookmaker,
        Producers producers,
        ExecutorService fetches,
        SideLoads sideLoads,
        ProfileCaches profiles,
        MatchCaches matches,
        MarketDescriptions markets,
        VoidReasons voidReasons,
        MatchStatusDescriptions statuses,
        Entities entities,
        SportsInfo sportsInfo,
        DescriptionManager descriptions,
        MessageFactory messages,
        Replay replay,
        FixtureChanges fixtureChanges,
        ClockOffsets offsets)
        implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FeedCore.class);

    /** How many side-loads each of the two queues holds. */
    static final int SIDE_LOAD_CAPACITY = 1_000;

    /** The most side-load workers, however high the REST concurrency limit is set. */
    static final int MAX_SIDE_LOAD_WORKERS = 16;

    /**
     * How many side-load workers a REST concurrency limit allows: half the data permits, so the
     * preload's loads leave the rest to the client's own reads, and at least one, at most
     * {@link #MAX_SIDE_LOAD_WORKERS}. With several, warm-ups run on at most half of them, so one
     * would not hold the only worker a match's competitors wait for; with one they can.
     */
    static int sideLoadWorkers(int restConcurrencyLimit) {
        return Math.clamp(restConcurrencyLimit / 2, 1, MAX_SIDE_LOAD_WORKERS);
    }

    /**
     * Asks the API who the bookmaker is and which producers there are, within the startup timeout,
     * then builds the rest, which asks nothing yet. Whatever it built is closed again when it fails.
     *
     * @param events the feed's events dispatcher, which the REST client reports to: the feed's own,
     *     started before and kept across the starts, so a start that fails leaves no thread of its
     *     own and a callback on it, which may be waiting for this very start, is never waited for
     * @param starting given the REST client before the first call, so a feed closed meanwhile can cut
     *     the start short by closing the client
     * @throws InitException when the API does not answer both in time, refuses the access token, or
     *     answers without what the feed needs
     */
    public static FeedCore start(
            OddsFeedConfiguration configuration, EventsDispatcher events, Consumer<ApiClient> starting) {
        return start(configuration, events, starting, FeedClock.system());
    }

    /**
     * As above, with the feed's clock: the one the caches, the catalogs, the loaders and the live
     * state count every age and deadline on, by its monotonic side, as the transport, the
     * dispatchers and the recovery actor do.
     */
    public static FeedCore start(
            OddsFeedConfiguration configuration,
            EventsDispatcher events,
            Consumer<ApiClient> starting,
            FeedClock clock) {
        return start(configuration, events, events, starting, clock);
    }

    /**
     * As above, with where the REST client reports its calls: the feed's events, and whoever else
     * is to know of them, such as the health.
     */
    public static FeedCore start(
            OddsFeedConfiguration configuration,
            EventsDispatcher events,
            ApiEvents apiEvents,
            Consumer<ApiClient> starting,
            FeedClock clock) {
        var api = new ApiClient(configuration, apiEvents);
        @Nullable ExecutorService fetches = null;
        @Nullable SideLoads sideLoads = null;
        try {
            starting.accept(api);
            var startup = Startup.fetch(api, configuration.getStartupTimeout());
            var bookmaker = Bookmaker.from(startup.bookmaker());
            if (bookmaker.expiresSoon(Instant.now())) {
                LOG.warn("Access token will expire soon ({})", bookmaker.expireAt());
            }
            var producers = new Producers(startup.producers());
            fetches = Executors.newVirtualThreadPerTaskExecutor();
            sideLoads = new SideLoads(
                    SIDE_LOAD_CAPACITY,
                    sideLoadWorkers(configuration.getRestConcurrencyLimit()),
                    configuration.getHttpClientTimeout(),
                    clock);
            return assemble(configuration, api, events, bookmaker, producers, fetches, sideLoads, clock);
        } catch (RuntimeException e) {
            api.close();
            if (sideLoads != null) {
                sideLoads.close();
            }
            if (fetches != null) {
                fetches.shutdownNow();
            }
            throw e;
        }
    }

    private static FeedCore assemble(
            OddsFeedConfiguration configuration,
            ApiClient api,
            EventsDispatcher events,
            Bookmaker bookmaker,
            Producers producers,
            ExecutorService fetches,
            SideLoads sideLoads,
            FeedClock clock) {
        var timeout = configuration.getHttpClientTimeout();
        var locale = configuration.getDefaultLocale();
        var strategy = configuration.getExceptionHandlingStrategy();
        var sizes = CacheSizes.from(configuration);
        var profiles = new ProfileCaches(api, timeout, sizes, fetches, clock);
        var matches = new MatchCaches(
                api, profiles, sideLoads, timeout, sizes, locale, fetches, clock, Takeover.from(configuration));
        var markets = new MarketDescriptions(api, timeout, fetches, fetches, clock);
        var voidReasons = new VoidReasons(api, timeout, fetches, fetches, clock);
        var statuses = new MatchStatusDescriptions(api, timeout, fetches, fetches, clock);
        var entities = new Entities(
                matches,
                profiles,
                new StatusDescriptions(statuses, fetches)::get,
                strategy,
                timeout,
                fetches,
                configuration.getRestConcurrencyLimit());
        var inDefaultLocale = List.of(locale);
        return new FeedCore(
                api,
                events,
                bookmaker,
                producers,
                fetches,
                sideLoads,
                profiles,
                matches,
                markets,
                voidReasons,
                statuses,
                entities,
                new SportsInfo(entities, api, locale),
                new DescriptionManager(markets, voidReasons, locale, strategy, fetches),
                new MessageFactory(entities, markets, strategy, locale),
                // 0.0.x made every id of the replay list a match in the default locale
                new Replay(api, id -> entities.match(id, inDefaultLocale), strategy),
                new FixtureChanges(clock),
                new ClockOffsets(producers));
    }

    /**
     * Starts loading the catalogs of each locale in the background - the market descriptions, the
     * match statuses and the sports - and the void reasons once, so the first messages and reads find
     * them: what the feed's open does for its preload locales. Waits for nothing; a load that fails
     * is logged and backs off no read, so the next read loads it as it would have without it, and one
     * the feed's close cuts short is not logged.
     */
    public void preload(List<Locale> locales) {
        if (locales.isEmpty()) {
            return;
        }
        preloadInBackground("the void reasons", voidReasons::preload);
        for (Locale locale : locales) {
            preloadInBackground("the market descriptions in " + locale, () -> markets.preload(locale));
            preloadInBackground("the match statuses in " + locale, () -> statuses.preload(locale));
            preloadInBackground("the sports in " + locale, () -> profiles.sports(locale, null));
        }
    }

    private void preloadInBackground(String what, Runnable load) {
        try {
            fetches.execute(() -> {
                try {
                    load.run();
                } catch (RuntimeException e) {
                    // one the feed's close cut short is no failure worth a warning
                    if (!api.isClosed()) {
                        LOG.warn("The preload of {} failed; its first read loads it: {}", what, e.toString());
                    }
                }
            });
        } catch (RejectedExecutionException closed) {
            // the feed closed: nothing to preload for
        }
    }

    /**
     * How each catalog is doing, for {@code getHealth()}: the market descriptions and their variants,
     * the void reasons and the match statuses, in that order.
     */
    public List<CatalogHealth> catalogs() {
        var all = new ArrayList<CatalogHealth>(markets.health());
        all.add(voidReasons.health());
        all.add(statuses.health());
        return all;
    }

    /** What the entity caches and their background loads have counted, for {@code getHealth()}. */
    public FeedHealth.Caches caches() {
        return new FeedHealth.Caches(
                sideLoads.dropped(),
                sideLoads.failed(),
                matches.discardedFetches() + profiles.discardedFetches(),
                matches.invalidationsForgotten() + profiles.invalidationsForgotten(),
                matches.liveStatesDropped(),
                fixtureChanges.evicted());
    }

    /**
     * Releases what {@link #start} built: the REST client first, so a call under way ends at once
     * and a load behind it starts none, then the loads. Waits for nothing; the events dispatcher is
     * the feed's to stop.
     */
    @Override
    public void close() {
        api.close();
        sideLoads.close();
        fetches.shutdownNow();
    }
}
