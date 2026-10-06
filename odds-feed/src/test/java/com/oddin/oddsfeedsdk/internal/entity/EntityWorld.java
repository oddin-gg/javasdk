package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.internal.loader.SideLoads;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * The entity façades over the real caches and REST client, against the fake API: what a test of the
 * façades needs, built the way the feed will build it.
 */
final class EntityWorld implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    final FakeRestServer api;
    final FakeTime time = new FakeTime();
    final ExecutorService threads;
    final ApiClient client;
    final ProfileCaches profiles;
    final SideLoads sideLoads;
    final MatchCaches matches;
    final Entities entities;
    final SportsInfo sportsInfo;

    private EntityWorld(
            FakeRestServer api, ExceptionHandlingStrategy strategy, boolean warm, ExecutorService threads, int fanOut) {
        this(api, strategy, warm ? new SideLoads(1_000, 4, TIMEOUT) : new SideLoads(1, 0, TIMEOUT), threads, fanOut);
    }

    private EntityWorld(
            FakeRestServer api,
            ExceptionHandlingStrategy strategy,
            SideLoads sideLoads,
            ExecutorService threads,
            int fanOut) {
        this.api = api;
        this.threads = threads;
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(TIMEOUT)
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        profiles = new ProfileCaches(client, TIMEOUT, CacheSizes.from(configuration), threads, time, time);
        // without workers, a side-load waits in the queue for good: the test sees only its own loads
        this.sideLoads = sideLoads;
        matches = new MatchCaches(
                client,
                profiles,
                sideLoads,
                TIMEOUT,
                CacheSizes.from(configuration),
                Locale.ENGLISH,
                threads,
                time,
                time);
        entities = new Entities(matches, profiles, EntityWorld::describe, strategy, TIMEOUT, threads, fanOut);
        sportsInfo = new SportsInfo(entities, client, Locale.ENGLISH);
    }

    /** No side-loads, so a test counts only the loads its getters make. */
    static EntityWorld start(ExceptionHandlingStrategy strategy) {
        return new EntityWorld(
                FakeRestServer.start(), strategy, false, Executors.newVirtualThreadPerTaskExecutor(), 16);
    }

    /** With side-loads of this capacity and these workers, for a test of what they take and drop. */
    static EntityWorld withSideLoads(int capacity, int workers) {
        return new EntityWorld(
                FakeRestServer.start(),
                ExceptionHandlingStrategy.THROW,
                new SideLoads(capacity, workers, TIMEOUT),
                Executors.newVirtualThreadPerTaskExecutor(),
                16);
    }

    /** With the side-loads a summary starts. */
    static EntityWorld warm(ExceptionHandlingStrategy strategy) {
        return new EntityWorld(FakeRestServer.start(), strategy, true, Executors.newVirtualThreadPerTaskExecutor(), 16);
    }

    /**
     * On platform threads throughout, which {@link java.lang.management.ThreadMXBean} sees, and with
     * the side-loads a summary starts: for the deadlock tests.
     */
    static EntityWorld onPlatformThreads(ExceptionHandlingStrategy strategy) {
        return new EntityWorld(FakeRestServer.start(), strategy, true, Executors.newCachedThreadPool(), 16);
    }

    @Override
    public void close() {
        sideLoads.close();
        threads.shutdownNow();
        client.close();
        api.close();
    }

    /** The match status catalog, as far as the tests need it: status 1 is "Ended". */
    private static @Nullable LocalizedStaticData describe(long id, List<Locale> locales) {
        if (id != 1) {
            return null;
        }
        var descriptions = new LinkedHashMap<Locale, String>();
        for (Locale locale : locales) {
            descriptions.put(locale, locale.equals(Locale.GERMAN) ? "Beendet" : "Ended");
        }
        return new Description(id, descriptions);
    }

    private record Description(long id, LinkedHashMap<Locale, String> descriptions) implements LocalizedStaticData {
        @Override
        public long getId() {
            return id;
        }

        @Override
        public String getDescription() {
            return descriptions.values().iterator().next();
        }

        @Override
        public @Nullable String getDescription(Locale locale) {
            return descriptions.get(locale);
        }
    }
}
