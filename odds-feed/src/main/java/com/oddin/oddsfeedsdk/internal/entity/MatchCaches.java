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
import com.oddin.oddsfeedsdk.internal.entity.ProfileCaches.Stamps;
import com.oddin.oddsfeedsdk.internal.loader.Loader;
import com.oddin.oddsfeedsdk.internal.loader.SideLoads;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       alone for the match status age. The winner is in it: the feed's when a message carries one,
 *       the summary's once the feed is quiet, and while it is not, the summary's when the feed has
 *       sent none.
 *   <li>A fixture change invalidates the match and its fixture; a load under way then writes nothing.
 *   <li>What a summary or a fixture says of the match's competitors, tournament and sport fills the
 *       profile caches. After a summary, the competitors' profiles in its locale are side-loaded, as
 *       0.0.x loaded them after every summary, so a reader of the competitors finds them warm; a
 *       full side-load queue drops them, and the reader loads them itself.
 * </ul>
 *
 * <p>Safe for concurrent use.
 */
public final class MatchCaches {

    private static final Logger LOG = LoggerFactory.getLogger(MatchCaches.class);

    /** How long a match or a fixture is fresh, as in 0.0.x. */
    static final Duration AGE = Duration.ofHours(12);

    /** How much longer than its deadline a caller waits for a load. */
    static final Duration MARGIN = Duration.ofSeconds(1);

    private final ApiClient client;
    private final ProfileCaches profiles;
    private final SideLoads sideLoads;
    private final Locale defaultLocale;
    private final InstantSource clock;
    private final EntityCache<URN> matches;
    private final EntityCache<URN> fixtures;
    private final LiveState<URN> live;
    private final Loader<MatchKey, Boolean> summaries;
    private final Loader<URN, Boolean> fixtureLoads;
    /** The match and locale of each preload queued or under way, so a burst of messages queues one. */
    private final Set<MatchKey> preloading = ConcurrentHashMap.newKeySet();
    /** Preloads that failed, for the log's count. */
    private final AtomicLong preloadsFailed = new AtomicLong();

    /**
     * @param profiles what a match's responses fill, and where its competitors are side-loaded
     * @param sideLoads where the competitors' profiles are loaded after a summary
     * @param timeout the HTTP client timeout, each load's deadline
     * @param sizes how many matches and fixtures are cached; the live state holds as many matches
     * @param fetches where the loads run: virtual threads
     */
    public MatchCaches(
            ApiClient client,
            ProfileCaches profiles,
            SideLoads sideLoads,
            Duration timeout,
            CacheSizes sizes,
            Locale defaultLocale,
            Executor fetches) {
        this(
                client,
                profiles,
                sideLoads,
                timeout,
                sizes,
                defaultLocale,
                fetches,
                InstantSource.system(),
                Ticker.systemTicker());
    }

    /** With the clocks a test drives. */
    MatchCaches(
            ApiClient client,
            ProfileCaches profiles,
            SideLoads sideLoads,
            Duration timeout,
            CacheSizes sizes,
            Locale defaultLocale,
            Executor fetches,
            InstantSource clock,
            Ticker ticker) {
        this.client = client;
        this.profiles = profiles;
        this.sideLoads = sideLoads;
        this.defaultLocale = defaultLocale;
        this.clock = clock;
        Duration longestFetch = timeout.plus(MARGIN);
        this.matches = new EntityCache<>("match", sizes.matches(), AGE, longestFetch, clock, ticker);
        this.fixtures = new EntityCache<>("fixture", sizes.fixtures(), AGE, longestFetch, clock, ticker);
        // one record per match, read with it
        this.live = new LiveState<>(sizes.matches(), ticker);
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
     * has none, as for a match the API does not describe.
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
     * A live message's status for the match, its winner included when it carries one: written unless
     * older than the last one from its producer, or too old to be live; written before the message
     * reaches the client, so its callback reads what the message carried.
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

    /**
     * A replayed message's status for the match, written whatever its timestamp and its age, in the
     * order the replay sends them: a run played again repeats the timestamps of the one before.
     */
    public void replayed(URN id, long producer, long timestamp, Instant receivedAt, OFSportEventStatus status) {
        live.feedWriteReplayed(id, producer, timestamp, receivedAt, MatchWrites.live(status));
    }

    /** The match changed: what is cached of it and of its fixture is out of date. */
    public void fixtureChange(URN id) {
        clear(id);
    }

    /** Drops what is cached of the match and of its fixture, as the public clear of one match does. */
    public void clear(URN id) {
        matches.invalidate(id);
        fixtures.invalidate(id);
    }

    /**
     * What a schedule or a list of matches said of each, and of their competitors, tournaments and
     * sports, as fills; {@code started} is what the caller took with {@link #startMany} before
     * fetching it.
     */
    public void fill(Collection<RASportEvent> events, Locale locale, Listed started) {
        for (RASportEvent event : events) {
            URN id = ApiValues.urn(event.getId());
            if (id != null) {
                matches.fill(id, MatchWrites.fill(SCHEDULE, event, locale), started.matches());
            }
            fillProfiles(event, locale, started.profiles());
        }
    }

    /** What a fetch of many matches takes before it starts, to hand to {@link #fill}. */
    public Listed startMany(BooleanSupplier abandoned) {
        return new Listed(matches.stampForMany(abandoned), profiles.startMany(abandoned));
    }

    /**
     * Loads the match in the background in each of {@code locales}, and with it its competitors: the
     * eager preload a message's match can get, so that its callback reads it warm. Never waits, and
     * makes no call itself: a load of the match in a locale already queued or under way is not
     * queued again, a full side-load queue drops it and counts it, and a reader loads what it needs
     * itself. A load that fails is logged, the first and every thousandth, and counted with the
     * side-loads.
     */
    public void preload(URN id, List<Locale> locales) {
        for (Locale locale : locales) {
            var key = new MatchKey(id, locale);
            if (!preloading.add(key)) {
                continue;
            }
            if (!sideLoads.offer(deadline -> preloadNow(key, deadline))) {
                preloading.remove(key);
            }
        }
    }

    private void preloadNow(MatchKey key, Deadline deadline) {
        try {
            match(key.id(), key.locale(), deadline);
        } catch (RuntimeException e) {
            long failed = preloadsFailed.incrementAndGet();
            if (failed == 1 || failed % 1_000 == 0) {
                LOG.warn(
                        "The eager preload could not load match {} in {}, {} so far: {}",
                        key.id(),
                        key.locale(),
                        failed,
                        e.toString());
            }
            // the side-loads count it
            throw e;
        } finally {
            preloading.remove(key);
        }
    }

    /** The preloads queued or under way; for a test. */
    int preloading() {
        return preloading.size();
    }

    /**
     * Queues a load that only warms what a reader may want later, such as the members of a list, to
     * run on the side-loads the others leave idle: it never takes the room, or every worker, that a
     * summary's competitors and a message's preload need. Never waits; a full queue drops it.
     */
    public boolean warmUp(SideLoads.Load load) {
        return sideLoads.offerWhenIdle(load);
    }

    /** The locale the fixtures are loaded in. */
    public Locale defaultLocale() {
        return defaultLocale;
    }

    /** Drops every cached match and fixture; the live state is the feed's, and stays. */
    public void clear() {
        matches.clear();
        fixtures.clear();
    }

    /** Fetch results of matches and fixtures thrown away as stale; for {@code getHealth()}. */
    public long discardedFetches() {
        return matches.discarded() + fixtures.discarded();
    }

    /**
     * Invalidations of matches and fixtures the size bound forgot while a fetch could still run;
     * for {@code getHealth()}.
     */
    public long invalidationsForgotten() {
        return matches.forgottenForRoom() + fixtures.forgottenForRoom();
    }

    /** Live states the feed wrote that the size bound dropped; for {@code getHealth()}. */
    public long liveStatesDropped() {
        return live.dropped();
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

    /** How many matches, fixtures and live states are held at most, in that order; for a test. */
    List<Long> bounds() {
        return List.of(matches.maximumSize(), fixtures.maximumSize(), live.maximumSize());
    }

    /** What is cached of the match, loading nothing; for a test. */
    @Nullable
    Entry cachedMatch(URN id) {
        return matches.get(id);
    }

    private Boolean fetchSummary(MatchKey key, Deadline deadline, BooleanSupplier abandoned) {
        Stamp started = matches.stamp(key.id(), abandoned);
        Stamps listed = profiles.startMany(abandoned);
        var summary = client.fetchMatchSummary(key.id(), key.locale(), deadline);
        var event = summary.getSportEvent();
        var status = summary.getSportEventStatus();
        // a summary that describes no match writes no live state either: the match is not found
        if (event == null) {
            return false;
        }
        boolean written = matches.writeAuthoritative(key.id(), MatchWrites.summary(event, key.locale()), started);
        fillProfiles(event, key.locale(), listed);
        if (written) {
            warmCompetitors(event, key.locale());
        }
        // the live state too gives way to a fixture change, or to a summary of another locale fetched
        // since; a summary without a status still says REST was asked, so it is not asked again
        BooleanSupplier outdated = () -> abandoned.getAsBoolean() || !matches.isNewest(key.id(), SUMMARY, started);
        boolean taken = live.restWriteIfQuiet(
                key.id(), clock.instant(), status == null ? LiveWrite.of() : MatchWrites.live(status), outdated);
        // while the feed owns the match, a winner the summary has and the feed has not sent still fills
        if (!taken && status != null) {
            live.restFill(key.id(), MatchWrites.winner(status), outdated);
        }
        return written;
    }

    private Boolean fetchFixture(URN id, Deadline deadline, BooleanSupplier abandoned) {
        Stamp fixtureStarted = fixtures.stamp(id, abandoned);
        Stamp matchStarted = matches.stamp(id, abandoned);
        Stamps listed = profiles.startMany(abandoned);
        RAFixture fixture = client.fetchFixture(id, defaultLocale, deadline).getFixture();
        if (fixture == null) {
            return false;
        }
        matches.fill(id, MatchWrites.fill(FIXTURE_OF_MATCH, fixture, defaultLocale), matchStarted);
        fillProfiles(fixture, defaultLocale, listed);
        return fixtures.writeAuthoritative(id, MatchWrites.fixture(fixture, defaultLocale), fixtureStarted);
    }

    /** What a match's response says of its competitors, its tournament and its sport, as fills. */
    private void fillProfiles(RASportEvent event, Locale locale, Stamps listed) {
        var competitors = event.getCompetitors();
        if (competitors != null) {
            profiles.fillCompetitors(competitors.getCompetitor(), locale, listed);
        }
        var tournament = event.getTournament();
        if (tournament != null) {
            profiles.fillTournament(tournament, locale, listed);
        }
    }

    /** Side-loads the profiles of the summary's competitors in its locale; never waits. */
    private void warmCompetitors(RASportEvent event, Locale locale) {
        var competitors = event.getCompetitors();
        if (competitors == null) {
            return;
        }
        for (var competitor : competitors.getCompetitor()) {
            URN id = ApiValues.urn(competitor.getId());
            if (id != null) {
                sideLoads.offer(deadline -> profiles.competitor(id, locale, deadline));
            }
        }
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

    /** What a fetch of many matches took of the match caches and of the profile caches before it started. */
    public record Listed(Stamp matches, Stamps profiles) {}

    /** A match in a locale: what a summary is loaded for. */
    private record MatchKey(URN id, Locale locale) {}
}
