package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.COMPETITOR_PROFILE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.PLAYER_PROFILE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_LIST;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_TOURNAMENT_LIST;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_INFO;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_LISTED;

import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.EntityCache;
import com.oddin.oddsfeedsdk.internal.cache.EntityCache.Stamp;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.internal.loader.Loader;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import com.oddin.oddsfeedsdk.schema.rest.v1.RACompetitors;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASport;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportExtended;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATeam;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournament;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The competitor, player, tournament and sport caches, and the loaders that fill them from the API.
 * A read loads what is missing or out of date in its locale and waits for it, joining a load under
 * way.
 *
 * <ul>
 *   <li>A competitor's profile is its authority, per locale; the players it lists fill the player
 *       cache, and a player's own profile is the authority on it.
 *   <li>A tournament's info is its authority; the competitors and the sport it names only fill.
 *   <li>The sport list is loaded whole, per locale, and is the authority on every sport in it; a
 *       sport's tournament list is loaded on its own.
 *   <li>What a match, a schedule or a tournament says of a competitor, a tournament or a sport only
 *       fills, and gives way to an invalidation since it was fetched.
 * </ul>
 *
 * <p>Safe for concurrent use.
 */
public final class ProfileCaches {

    /** Tournaments and sports each, which no option sizes. */
    static final long SIZE = 10_000;

    /** How long a competitor, a player or a sport is fresh, as in 0.0.x. */
    static final Duration PROFILE_AGE = Duration.ofHours(24);

    /** How long a tournament is fresh, as in 0.0.x. */
    static final Duration TOURNAMENT_AGE = Duration.ofHours(12);

    private final ApiClient client;
    private final InstantSource clock;
    private final EntityCache<URN> competitors;
    private final EntityCache<URN> players;
    private final EntityCache<URN> tournaments;
    private final EntityCache<URN> sports;
    private final Loader<Key, Boolean> competitorLoads;
    private final Loader<Key, Boolean> playerLoads;
    private final Loader<Key, Boolean> tournamentLoads;
    private final Loader<Key, Boolean> sportTournamentLoads;
    private final Loader<Locale, Boolean> sportListLoads;
    /** The sports each locale's list named, and when it was loaded. */
    private final ConcurrentHashMap<Locale, SportList> sportLists = new ConcurrentHashMap<>();

    /**
     * @param timeout the HTTP client timeout, each load's deadline
     * @param sizes how many competitors and players are cached
     * @param fetches where the loads run: virtual threads
     */
    public ProfileCaches(ApiClient client, Duration timeout, CacheSizes sizes, Executor fetches) {
        this(client, timeout, sizes, fetches, InstantSource.system(), Ticker.systemTicker());
    }

    /** With the clocks a test drives. */
    ProfileCaches(
            ApiClient client,
            Duration timeout,
            CacheSizes sizes,
            Executor fetches,
            InstantSource clock,
            Ticker ticker) {
        this.client = client;
        this.clock = clock;
        Duration longestFetch = timeout.plus(MatchCaches.MARGIN);
        this.competitors =
                new EntityCache<>("competitor", sizes.competitors(), PROFILE_AGE, longestFetch, clock, ticker);
        this.players = new EntityCache<>("player", sizes.players(), PROFILE_AGE, longestFetch, clock, ticker);
        this.tournaments = new EntityCache<>("tournament", SIZE, TOURNAMENT_AGE, longestFetch, clock, ticker);
        this.sports = new EntityCache<>("sport", SIZE, PROFILE_AGE, longestFetch, clock, ticker);
        Duration margin = MatchCaches.MARGIN;
        this.competitorLoads = new Loader<>("competitor", this::fetchCompetitor, timeout, margin, fetches);
        this.playerLoads = new Loader<>("player", this::fetchPlayer, timeout, margin, fetches);
        this.tournamentLoads = new Loader<>("tournament", this::fetchTournament, timeout, margin, fetches);
        this.sportTournamentLoads =
                new Loader<>("sport tournaments", this::fetchSportTournaments, timeout, margin, fetches);
        this.sportListLoads = new Loader<>("sports", this::fetchSportList, timeout, margin, fetches);
    }

    /** The competitor as its profile in {@code locale} describes it, loaded when missing or out of date. */
    public Entry competitor(URN id, Locale locale, @Nullable Deadline within) {
        return read(
                competitors, id, COMPETITOR_PROFILE, locale, () -> competitorLoads.load(new Key(id, locale), within));
    }

    /** The player as its profile in {@code locale} describes it, loaded when missing or out of date. */
    public Entry player(URN id, Locale locale, @Nullable Deadline within) {
        return read(players, id, PLAYER_PROFILE, locale, () -> playerLoads.load(new Key(id, locale), within));
    }

    /** Whether the competitor's profile in {@code locale} needs no load: fresh, or a load of it under way. */
    public boolean competitorWarm(URN id, Locale locale) {
        return warm(competitors, id, COMPETITOR_PROFILE, locale, competitorLoads);
    }

    /** Whether the player's profile in {@code locale} needs no load: fresh, or a load of it under way. */
    public boolean playerWarm(URN id, Locale locale) {
        return warm(players, id, PLAYER_PROFILE, locale, playerLoads);
    }

    /** The tournament as its info in {@code locale} describes it, loaded when missing or out of date. */
    public Entry tournament(URN id, Locale locale, @Nullable Deadline within) {
        return read(tournaments, id, TOURNAMENT_INFO, locale, () -> tournamentLoads.load(new Key(id, locale), within));
    }

    /**
     * The sport as the sport list in {@code locale} describes it, the list loaded when out of date -
     * or when it names the sport and the sport's own entry is gone or out of date, which the size
     * bound or a clear under way can leave behind a list still fresh.
     */
    public Entry sport(URN id, Locale locale, @Nullable Deadline within) {
        Entry entry = entryOf(sports, id);
        // twice at most: a read just after a clear can join a load from before it, which writes nothing
        for (int tries = 0; tries < 2 && !sportFresh(id, entry, locale); tries++) {
            sportListLoads.load(locale, within);
            entry = entryOf(sports, id);
        }
        return entry;
    }

    /** The sports the list in {@code locale} names, in its order, the list loaded when out of date. */
    public List<URN> sports(Locale locale, @Nullable Deadline within) {
        SportList list = freshList(locale);
        for (int tries = 0; tries < 2 && list == null; tries++) {
            sportListLoads.load(locale, within);
            list = freshList(locale);
        }
        return list == null ? List.of() : list.ids();
    }

    /** The sport with its tournament list in {@code locale}, loaded when missing or out of date. */
    public Entry sportTournaments(URN sportId, Locale locale, @Nullable Deadline within) {
        return read(
                sports,
                sportId,
                SPORT_TOURNAMENT_LIST,
                locale,
                () -> sportTournamentLoads.load(new Key(sportId, locale), within));
    }

    /** What a fetch of a match, a schedule or a list takes before it starts, to hand to the fills. */
    public Stamps startMany(BooleanSupplier abandoned) {
        return new Stamps(
                competitors.stampForMany(abandoned),
                players.stampForMany(abandoned),
                tournaments.stampForMany(abandoned),
                sports.stampForMany(abandoned));
    }

    /** What a response said of competitors, as fills. */
    public void fillCompetitors(Collection<? extends RATeam> teams, Locale locale, Stamps started) {
        for (RATeam team : teams) {
            URN id = ApiValues.urn(team.getId());
            if (id != null) {
                competitors.fill(id, ProfileWrites.listedTeam(team, locale), started.competitors());
            }
        }
    }

    /** What a response said of a tournament, and of the sport it names, as fills. */
    public void fillTournament(RATournament tournament, Locale locale, Stamps started) {
        URN id = ApiValues.urn(tournament.getId());
        if (id != null) {
            tournaments.fill(
                    id, ProfileWrites.tournament(TOURNAMENT_LISTED, tournament, null, locale), started.tournaments());
        }
        fillSport(tournament.getSport(), locale, started.sports());
    }

    /** Drops what is cached of the competitor, as the public clear of one competitor does. */
    public void clearCompetitor(URN id) {
        competitors.invalidate(id);
    }

    /** Drops what is cached of the tournament, as the public clear of one tournament does. */
    public void clearTournament(URN id) {
        tournaments.invalidate(id);
    }

    /** Drops every cached competitor, player, tournament and sport, as the public clear does. */
    public void clear() {
        competitors.clear();
        players.clear();
        tournaments.clear();
        sports.clear();
        sportLists.clear();
    }

    /**
     * Fetch results of competitors, players, tournaments and sports thrown away as stale; for
     * {@code getHealth()}.
     */
    public long discardedFetches() {
        return competitors.discarded() + players.discarded() + tournaments.discarded() + sports.discarded();
    }

    /**
     * Invalidations of competitors, players, tournaments and sports the size bound forgot while a
     * fetch could still run; for {@code getHealth()}.
     */
    public long invalidationsForgotten() {
        return competitors.forgottenForRoom()
                + players.forgottenForRoom()
                + tournaments.forgottenForRoom()
                + sports.forgottenForRoom();
    }

    /**
     * How many competitors, players, tournaments and sports are held at most, in that order; for a
     * test.
     */
    List<Long> bounds() {
        return List.of(
                competitors.maximumSize(), players.maximumSize(), tournaments.maximumSize(), sports.maximumSize());
    }

    /** What is cached of the competitor, loading nothing; for a test. */
    @Nullable
    Entry cachedCompetitor(URN id) {
        return competitors.get(id);
    }

    /** What is cached of the player, loading nothing: for a test, and for a player's shared fields once loaded. */
    @Nullable
    Entry cachedPlayer(URN id) {
        return players.get(id);
    }

    /** What is cached of the tournament, loading nothing; for a test. */
    @Nullable
    Entry cachedTournament(URN id) {
        return tournaments.get(id);
    }

    /** What is cached of the sport, loading nothing; for a test. */
    @Nullable
    Entry cachedSport(URN id) {
        return sports.get(id);
    }

    /**
     * The entry once {@code endpoint} is fresh for it in {@code locale}, loaded twice at most: a read
     * just after a clear can join a load from before it, which writes nothing. Freshness is told from
     * the entry returned, so a clear after the check cannot leave the read with a tombstone.
     */
    private Entry read(EntityCache<URN> cache, URN id, Endpoint endpoint, Locale locale, Runnable load) {
        Entry entry = entryOf(cache, id);
        for (int tries = 0; tries < 2 && !entry.isFresh(endpoint, locale, clock.instant(), cache.age()); tries++) {
            load.run();
            entry = entryOf(cache, id);
        }
        return entry;
    }

    private boolean warm(EntityCache<URN> cache, URN id, Endpoint endpoint, Locale locale, Loader<Key, Boolean> loads) {
        return entryOf(cache, id).isFresh(endpoint, locale, clock.instant(), cache.age())
                || loads.keys().contains(new Key(id, locale));
    }

    /** Whether the locale's list is fresh, and so is the sport's entry when the list names it. */
    private boolean sportFresh(URN id, Entry entry, Locale locale) {
        SportList list = freshList(locale);
        return list != null
                && (!list.ids().contains(id) || entry.isFresh(SPORT_LIST, locale, clock.instant(), sports.age()));
    }

    /** The locale's sport list, unless it is missing or out of date. */
    private @Nullable SportList freshList(Locale locale) {
        SportList list = sportLists.get(locale);
        return list != null && !list.loadedAt().plus(PROFILE_AGE).isBefore(clock.instant()) ? list : null;
    }

    private Boolean fetchCompetitor(Key key, Deadline deadline, BooleanSupplier abandoned) {
        Stamp started = competitors.stamp(key.id(), abandoned);
        Stamp listed = players.stampForMany(abandoned);
        Stamp sportsListed = sports.stampForMany(abandoned);
        var profile = client.fetchCompetitorProfile(key.id(), key.locale(), deadline);
        var team = profile.getCompetitor();
        if (team == null) {
            return false;
        }
        List<RAPlayer> listedPlayers = profile.getPlayersElement() == null ? null : profile.getPlayers();
        if (listedPlayers != null) {
            for (RAPlayer player : listedPlayers) {
                URN id = ApiValues.urn(player.getId());
                if (id != null) {
                    players.fill(id, ProfileWrites.listedPlayer(player, key.locale()), listed);
                }
            }
        }
        fillSport(team.getSport(), key.locale(), sportsListed);
        return competitors.writeAuthoritative(
                key.id(), ProfileWrites.competitor(team, listedPlayers, key.locale()), started);
    }

    private Boolean fetchPlayer(Key key, Deadline deadline, BooleanSupplier abandoned) {
        Stamp started = players.stamp(key.id(), abandoned);
        var player = client.fetchPlayerProfile(key.id(), key.locale(), deadline).getPlayer();
        return player != null
                && players.writeAuthoritative(key.id(), ProfileWrites.player(player, key.locale()), started);
    }

    private Boolean fetchTournament(Key key, Deadline deadline, BooleanSupplier abandoned) {
        Stamp started = tournaments.stamp(key.id(), abandoned);
        Stamps listed = startMany(abandoned);
        var info = client.fetchTournament(key.id(), key.locale(), deadline);
        var tournament = info.getTournament();
        if (tournament == null) {
            return false;
        }
        RACompetitors own = tournament.getCompetitors();
        // the element's own list, unless it lists none: then the info's, as 0.0.x read it
        RACompetitors competitorList = (own != null && !own.getCompetitor().isEmpty()) || info.getCompetitors() == null
                ? own
                : info.getCompetitors();
        if (own != null) {
            fillCompetitors(own.getCompetitor(), key.locale(), listed);
        }
        if (info.getCompetitors() != null) {
            fillCompetitors(info.getCompetitors().getCompetitor(), key.locale(), listed);
        }
        fillSport(tournament.getSport(), key.locale(), listed.sports());
        return tournaments.writeAuthoritative(
                key.id(), ProfileWrites.tournament(TOURNAMENT_INFO, tournament, competitorList, key.locale()), started);
    }

    private Boolean fetchSportTournaments(Key key, Deadline deadline, BooleanSupplier abandoned) {
        Stamp started = sports.stamp(key.id(), abandoned);
        Stamp listed = tournaments.stampForMany(abandoned);
        var response = client.fetchTournaments(key.id(), key.locale(), deadline);
        var listElement = response.getTournaments();
        List<RATournament> list = listElement == null ? List.of() : listElement.getTournament();
        for (RATournament tournament : list) {
            URN id = ApiValues.urn(tournament.getId());
            if (id != null) {
                tournaments.fill(
                        id, ProfileWrites.tournament(TOURNAMENT_LISTED, tournament, null, key.locale()), listed);
            }
        }
        // a sport without the list's element still counts as loaded, with nothing in it
        return sports.writeAuthoritative(key.id(), ProfileWrites.sportTournaments(list, key.locale()), started);
    }

    private Boolean fetchSportList(Locale locale, Deadline deadline, BooleanSupplier abandoned) {
        Stamp started = sports.stampForMany(abandoned);
        Instant at = clock.instant();
        var ids = new ArrayList<URN>();
        boolean allWritten = true;
        for (RASportExtended sport : client.fetchSports(locale, deadline).getSport()) {
            URN id = ApiValues.urn(sport.getId());
            if (id != null) {
                ids.add(id);
                allWritten &= sports.writeAuthoritative(id, ProfileWrites.sport(sport, locale), started);
            }
        }
        // a list a clear overtook, or one a newer list overtook, is not the one to read from
        if (allWritten && !abandoned.getAsBoolean()) {
            var list = new SportList(List.copyOf(ids), at);
            sportLists.put(locale, list);
            // a clear clears the sports before the lists: one since the fetch started, done before
            // the put or not, either dropped the list after it or shows here
            if (sports.clearedSince(started)) {
                sportLists.remove(locale, list);
                return false;
            }
        }
        return allWritten;
    }

    private void fillSport(@Nullable RASport sport, Locale locale, Stamp started) {
        if (sport == null) {
            return;
        }
        URN id = ApiValues.urn(sport.getId());
        if (id != null) {
            sports.fill(id, ProfileWrites.listedSport(sport, locale), started);
        }
    }

    private static Entry entryOf(EntityCache<URN> cache, URN id) {
        Entry entry = cache.get(id);
        return entry != null ? entry : Entry.none();
    }

    /** What a fetch of many entities took of each cache before it started. */
    public record Stamps(Stamp competitors, Stamp players, Stamp tournaments, Stamp sports) {}

    /** An entity in a locale: what its profile is loaded for. */
    private record Key(URN id, Locale locale) {}

    /** A locale's sport list: the sports it named, and when it was loaded. */
    private record SportList(List<URN> ids, Instant loadedAt) {}
}
