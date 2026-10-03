package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.FIXTURE;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.FIXTURE_OF_MATCH;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SCHEDULE;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SUMMARY;

import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.EntityCache;
import com.oddin.oddsfeedsdk.internal.cache.EntityCache.Stamp;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.internal.cache.LiveState;
import com.oddin.oddsfeedsdk.internal.cache.LiveState.LiveValues;
import com.oddin.oddsfeedsdk.internal.cache.LiveWrite;
import com.oddin.oddsfeedsdk.internal.loader.Loader;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixture;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEvent;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The match and fixture caches, the live state of matches, and the loaders that fill them from the
 * API. A read loads what is missing or out of date and waits for it, joining a load under way; the
 * feed's live status and its fixture changes come in from the dispatchers.
 *
 * <ul>
 *   <li>A match's summary is loaded per locale: the name is the locale's, the rest is shared. The
 *       same response offers the live status to the live state, which takes it only while the feed
 *       is quiet on the match.
 *   <li>A fixture is loaded once, in the default locale, as 0.0.x did; it fills what the match has
 *       not had from a summary.
 *   <li>The live state is loaded again, from the summary, once the feed and REST have both left it
 *       alone for the match status age.
 *   <li>A fixture change invalidates the match and its fixture; a load under way then writes nothing.
 * </ul>
 *
 * <p>Safe for concurrent use.
 */
public final class MatchCaches {

    /** Matches, fixtures and live states each cache: a match's worth of data is small. */
    static final long SIZE = 10_000;

    /** How long a match or a fixture is fresh, as in 0.0.x. */
    static final Duration AGE = Duration.ofHours(12);

    /** How much longer than its deadline a caller waits for a load. */
    static final Duration MARGIN = Duration.ofSeconds(1);

    private final ApiClient client;
    private final Locale defaultLocale;
    private final InstantSource clock;
    private final EntityCache<URN> matches;
    private final EntityCache<URN> fixtures;
    private final LiveState<URN> live;
    private final Loader<MatchKey, Boolean> summaries;
    private final Loader<URN, Boolean> fixtureLoads;

    /**
     * @param timeout the HTTP client timeout, each load's deadline
     * @param fetches where the loads run: virtual threads
     */
    public MatchCaches(ApiClient client, Duration timeout, Locale defaultLocale, Executor fetches) {
        this(client, timeout, defaultLocale, fetches, InstantSource.system(), Ticker.systemTicker());
    }

    /** With the clocks a test drives. */
    MatchCaches(
            ApiClient client,
            Duration timeout,
            Locale defaultLocale,
            Executor fetches,
            InstantSource clock,
            Ticker ticker) {
        this.client = client;
        this.defaultLocale = defaultLocale;
        this.clock = clock;
        Duration longestFetch = timeout.plus(MARGIN);
        this.matches = new EntityCache<>("match", SIZE, AGE, longestFetch, clock, ticker);
        this.fixtures = new EntityCache<>("fixture", SIZE, AGE, longestFetch, clock, ticker);
        this.live = new LiveState<>(SIZE, ticker);
        this.summaries = new Loader<>("match", this::fetchSummary, timeout, MARGIN, fetches);
        this.fixtureLoads = new Loader<>("fixture", this::fetchFixture, timeout, MARGIN, fetches);
    }

    /**
     * The match as its summary in {@code locale} describes it, loaded when that is missing or older
     * than {@link #AGE}. An entry with nothing in it is a match the API has not described, or one
     * invalidated again while it loaded.
     *
     * @throws com.oddin.oddsfeedsdk.exceptions.ApiException when the load fails or does not finish
     */
    public Entry match(URN id, Locale locale) {
        return match(id, locale, null);
    }

    /** The same, from inside another load, waiting no longer than its deadline. */
    public Entry match(URN id, Locale locale, @Nullable Deadline within) {
        var key = new MatchKey(id, locale);
        return read(matches, id, SUMMARY, locale, () -> summaries.load(key, within));
    }

    /** The match's fixture, loaded in the default locale when it is missing or out of date. */
    public Entry fixture(URN id) {
        return read(fixtures, id, FIXTURE, defaultLocale, () -> fixtureLoads.load(id));
    }

    /**
     * The match's live state, its summary loaded again when neither the feed nor REST wrote it within
     * the match status age. A summary without a status leaves a live state without values, fresh as
     * any. Loaded once more when a change since refused the reload's write; null when even then it
     * has none.
     *
     * @throws com.oddin.oddsfeedsdk.exceptions.ApiException when the load fails or does not finish
     */
    public @Nullable LiveValues live(URN id) {
        LiveValues values = live.get(id);
        if (values != null && values.isFresh(clock.instant())) {
            return values;
        }
        var key = new MatchKey(id, defaultLocale);
        for (int tries = 0; tries < 2 && (values == null || !values.isFresh(clock.instant())); tries++) {
            summaries.load(key);
            values = live.get(id);
        }
        return values;
    }

    /**
     * A live message's status for the match: written unless older than the last one from its
     * producer, or too old to be live; written before the message reaches the client, so its
     * callback reads what the message carried.
     *
     * @return whether it was written
     */
    public boolean oddsChange(
            URN id,
            long producer,
            long timestamp,
            Duration correctedAge,
            Instant receivedAt,
            OFSportEventStatus status) {
        return live.feedWriteIfNewer(id, producer, timestamp, correctedAge, receivedAt, MatchWrites.live(status));
    }

    /** The match changed: what is cached of it and of its fixture is out of date. */
    public void fixtureChange(URN id) {
        matches.invalidate(id);
        fixtures.invalidate(id);
    }

    /**
     * What a schedule or a list of matches said of each, as fills; {@code started} is what the
     * caller took with {@link #startMany} before fetching it.
     */
    public void fill(Collection<RASportEvent> events, Locale locale, Stamp started) {
        for (RASportEvent event : events) {
            URN id = ApiValues.urn(event.getId());
            if (id != null) {
                matches.fill(id, MatchWrites.fill(SCHEDULE, event, locale), started);
            }
        }
    }

    /** What a fetch of many matches takes before it starts, to hand to {@link #fill}. */
    public Stamp startMany(BooleanSupplier abandoned) {
        return matches.stampForMany(abandoned);
    }

    /** Drops every cached match and fixture; the live state is the feed's, and stays. */
    public void clear() {
        matches.clear();
        fixtures.clear();
    }

    /** The match's live state as it is, loading nothing; for a test. */
    @Nullable
    LiveValues cachedLive(URN id) {
        return live.get(id);
    }

    /** What is cached of the fixture, loading nothing; for a test. */
    @Nullable
    Entry cachedFixture(URN id) {
        return fixtures.get(id);
    }

    /** What is cached of the match, loading nothing; for a test. */
    @Nullable
    Entry cachedMatch(URN id) {
        return matches.get(id);
    }

    private Boolean fetchSummary(MatchKey key, Deadline deadline, BooleanSupplier abandoned) {
        Stamp started = matches.stamp(key.id(), abandoned);
        var summary = client.fetchMatchSummary(key.id(), key.locale(), deadline);
        var event = summary.getSportEvent();
        var status = summary.getSportEventStatus();
        boolean written = false;
        if (event != null) {
            written = matches.writeAuthoritative(key.id(), MatchWrites.summary(event, status, key.locale()), started);
        }
        // the live state too gives way to a fixture change, or to a summary of another locale fetched
        // since; a summary without a status still says REST was asked, so it is not asked again
        live.restWriteIfQuiet(
                key.id(),
                clock.instant(),
                status == null ? LiveWrite.of() : MatchWrites.live(status),
                () -> abandoned.getAsBoolean() || !matches.isNewest(key.id(), SUMMARY, started));
        return written;
    }

    private Boolean fetchFixture(URN id, Deadline deadline, BooleanSupplier abandoned) {
        Stamp fixtureStarted = fixtures.stamp(id, abandoned);
        Stamp matchStarted = matches.stamp(id, abandoned);
        RAFixture fixture = client.fetchFixture(id, defaultLocale, deadline).getFixture();
        if (fixture == null) {
            return false;
        }
        matches.fill(id, MatchWrites.fill(FIXTURE_OF_MATCH, fixture, defaultLocale), matchStarted);
        return fixtures.writeAuthoritative(id, MatchWrites.fixture(fixture, defaultLocale), fixtureStarted);
    }

    /**
     * The entry once {@code endpoint} is fresh for it in {@code locale}, loaded twice at most: a read
     * just after a fixture change can join a load from before it, which then writes nothing.
     * Freshness is told from the entry returned, so an invalidation after the check cannot leave the
     * read with a tombstone.
     */
    private Entry read(EntityCache<URN> cache, URN id, Endpoint endpoint, Locale locale, Runnable load) {
        Entry entry = entryOf(cache, id);
        for (int tries = 0; tries < 2 && !entry.isFresh(endpoint, locale, clock.instant(), cache.age()); tries++) {
            load.run();
            entry = entryOf(cache, id);
        }
        return entry;
    }

    private static Entry entryOf(EntityCache<URN> cache, URN id) {
        Entry entry = cache.get(id);
        return entry != null ? entry : Entry.none();
    }

    /** A match in a locale: what a summary is loaded for. */
    private record MatchKey(URN id, Locale locale) {}
}
