package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_NAME;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_TOURNAMENTS;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_TOURNAMENT_LIST;

import com.oddin.oddsfeedsdk.api.SportsInfoManager;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.FixtureChange;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixtureChange;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScheduleEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEvent;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's {@link SportsInfoManager}: the entities by id, the sports with their tournaments, and
 * the schedules, over the caches. An entity it returns loads nothing until a getter is called, as in
 * 0.0.x; a list loads what it needs to know its members - the sport list, a sport's tournament list,
 * a schedule - and what a schedule says of its matches fills the caches.
 *
 * <p>A method that cannot load what it needs follows the exception strategy, as an entity's getters
 * do: {@code THROW} throws, {@code CATCH} returns null. What is thrown is what 0.0.x threw: the
 * API's own exception for a list the API is asked for directly, such as a schedule or a sport's
 * available tournaments, and an {@link com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException} for
 * a list built from the caches. The sports and the active tournaments keep 0.0.x's answer to an
 * outage, an empty list under either strategy, and a sport whose tournaments cannot be loaded is
 * left out of the active ones under {@code CATCH}. Every list is a new one, the caller's own.
 *
 * <p>Safe for concurrent use.
 */
public final class SportsInfo implements SportsInfoManager {

    private static final Logger LOG = LoggerFactory.getLogger(SportsInfo.class);

    private final Entities entities;
    private final ApiClient client;
    private final Locale defaultLocale;

    public SportsInfo(Entities entities, ApiClient client, Locale defaultLocale) {
        this.entities = entities;
        this.client = client;
        this.defaultLocale = defaultLocale;
    }

    @Override
    public @Nullable List<Sport> getSports() {
        return getSports(defaultLocale);
    }

    /**
     * The sports of the sport list in {@code locale}, in its order; none when the list cannot be
     * loaded, under either strategy, as in 0.0.x.
     */
    @Override
    public @Nullable List<Sport> getSports(Locale locale) {
        var sports = new ArrayList<Sport>();
        for (URN id : sportsOrNone(locale)) {
            sports.add(entities.sport(id, List.of(locale)));
        }
        return sports;
    }

    @Override
    public @Nullable List<Tournament> getActiveTournaments() {
        return getActiveTournaments(defaultLocale);
    }

    /**
     * The tournaments of every sport, each sport's list loaded side by side. As in 0.0.x, never null:
     * none when the sport list cannot be loaded, under either strategy; a sport whose tournaments
     * cannot be loaded fails it under {@code THROW}, and is left out under {@code CATCH}.
     */
    @Override
    public @Nullable List<Tournament> getActiveTournaments(Locale locale) {
        List<Tournament> active = entities.guard("active tournaments", () -> {
            List<List<Tournament>> perSport = entities.each(sportsOrNone(locale), sport -> {
                List<Tournament> ofSport = entities.guard("sport " + sport, () -> tournamentsOf(sport, locale));
                return ofSport == null ? List.<Tournament>of() : ofSport;
            });
            var tournaments = new ArrayList<Tournament>();
            perSport.forEach(tournaments::addAll);
            return tournaments;
        });
        return active == null ? new ArrayList<>() : active;
    }

    @Override
    public @Nullable List<Tournament> getActiveTournaments(String sportName) {
        return getActiveTournaments(sportName, defaultLocale);
    }

    /**
     * The tournaments of the sport with this name in {@code locale}, whatever its case. As in 0.0.x,
     * never null: none when the sport list has no such sport or cannot be loaded, and under {@code
     * CATCH} none when the sport's tournaments cannot be.
     */
    @Override
    public @Nullable List<Tournament> getActiveTournaments(String sportName, Locale locale) {
        List<Tournament> tournaments = entities.guard("active tournaments of " + sportName, () -> {
            for (URN sport : sportsOrNone(locale)) {
                String name = entities.profiles.sport(sport, locale, null).get(SPORT_NAME, locale);
                if (sportName.equalsIgnoreCase(name)) {
                    return tournamentsOf(sport, locale);
                }
            }
            return new ArrayList<Tournament>();
        });
        return tournaments == null ? new ArrayList<>() : tournaments;
    }

    @Override
    public @Nullable List<Match> getMatchesFor(Date date) {
        return getMatchesFor(date, defaultLocale);
    }

    /** The matches scheduled on that day, in UTC, as 0.0.x asked for them. */
    @Override
    public @Nullable List<Match> getMatchesFor(Date date, Locale locale) {
        var day = date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        return entities.guardCall(
                "matches for " + day, () -> scheduled(locale, () -> client.fetchMatches(day, locale)));
    }

    @Override
    public @Nullable List<Match> getLiveMatches() {
        return getLiveMatches(defaultLocale);
    }

    @Override
    public @Nullable List<Match> getLiveMatches(Locale locale) {
        return entities.guardCall("live matches", () -> scheduled(locale, () -> client.fetchLiveMatches(locale)));
    }

    @Override
    public @Nullable Match getMatch(URN id) {
        return getMatch(id, defaultLocale);
    }

    @Override
    public @Nullable Match getMatch(URN id, Locale locale) {
        return entities.match(id, List.of(locale));
    }

    @Override
    public @Nullable Competitor getCompetitor(URN id) {
        return getCompetitor(id, defaultLocale);
    }

    @Override
    public @Nullable Competitor getCompetitor(URN id, Locale locale) {
        return entities.competitor(id, List.of(locale));
    }

    @Override
    public @Nullable Player getPlayer(URN id, Locale locale) {
        return entities.player(id, List.of(locale));
    }

    @Override
    public @Nullable List<FixtureChange> getFixtureChanges() {
        return getFixtureChanges(defaultLocale);
    }

    /** The changes the API lists; one without an id that is a URN, or without its time, is left out. */
    @Override
    public @Nullable List<FixtureChange> getFixtureChanges(Locale locale) {
        return entities.guardCall("fixture changes", () -> {
            var changes = new ArrayList<FixtureChange>();
            for (RAFixtureChange change : client.fetchFixtureChanges(locale).getFixtureChange()) {
                URN id = ApiValues.urn(change.getSportEventId());
                Instant at = ApiValues.instant(change.getUpdateTime());
                if (id != null && at != null) {
                    changes.add(new Change(id, Date.from(at)));
                }
            }
            return changes;
        });
    }

    @Override
    public @Nullable List<Match> getListOfMatches(int startIndex, int limit) {
        return getListOfMatches(startIndex, limit, defaultLocale);
    }

    /**
     * A page of the matches with prematch odds.
     *
     * @throws IllegalStateException under either strategy, as in 0.0.x, unless {@code startIndex} is
     *     0 or more and {@code limit} 1 to 100
     */
    @Override
    public @Nullable List<Match> getListOfMatches(int startIndex, int limit, Locale locale) {
        if (startIndex < 0 || limit < 1 || limit > 100) {
            throw new IllegalStateException("Requires startIndex >= 0 && limit <= 100 && limit >= 1");
        }
        return entities.guardCall(
                "list of matches", () -> scheduled(locale, () -> client.fetchSchedule(startIndex, limit, locale)));
    }

    @Override
    public @Nullable List<Tournament> getAvailableTournaments(URN sportId) {
        return getAvailableTournaments(sportId, defaultLocale);
    }

    @Override
    public @Nullable List<Tournament> getAvailableTournaments(URN sportId, Locale locale) {
        return entities.guardCall("tournaments of sport " + sportId, () -> tournamentsOf(sportId, locale));
    }

    /** Drops what is cached of the match and of its fixture; the next read loads them again. */
    @Override
    public void clearMatch(URN id) {
        entities.matches.clear(id);
    }

    @Override
    public void clearTournament(URN id) {
        entities.profiles.clearTournament(id);
    }

    @Override
    public void clearCompetitor(URN id) {
        entities.profiles.clearCompetitor(id);
    }

    /** The sport list in {@code locale}; none when it cannot be loaded, as 0.0.x answered then. */
    private List<URN> sportsOrNone(Locale locale) {
        try {
            return entities.profiles.sports(locale, null);
        } catch (RuntimeException failed) {
            LOG.debug("sports in {} could not be loaded; none, as in 0.0.x", locale, failed);
            return List.of();
        }
    }

    /** The tournaments of the sport's own list in {@code locale}. */
    private List<Tournament> tournamentsOf(URN sport, Locale locale) {
        List<URN> ids = Entities.found(
                        entities.profiles.sportTournaments(sport, locale, null),
                        SPORT_TOURNAMENT_LIST,
                        locale,
                        "sport " + sport)
                .get(SPORT_TOURNAMENTS, null);
        var tournaments = new ArrayList<Tournament>();
        for (URN id : ids == null ? List.<URN>of() : ids) {
            tournaments.add(entities.tournament(id, sport, List.of(locale)));
        }
        return tournaments;
    }

    /**
     * The matches of a schedule, in its order. What it says of each fills the caches, given way to
     * any change since it was asked for; a match with an id that is not a URN is left out.
     */
    private List<Match> scheduled(Locale locale, Supplier<RAScheduleEndpoint> fetch) {
        var started = entities.matches.startMany(() -> false);
        List<RASportEvent> events = fetch.get().getSportEvent();
        entities.matches.fill(events, locale, started);
        var matches = new ArrayList<Match>(events.size());
        for (RASportEvent event : events) {
            URN id = ApiValues.urn(event.getId());
            if (id != null) {
                matches.add(entities.match(id, List.of(locale)));
            }
        }
        return matches;
    }

    /** A fixture change as the API lists it. */
    private record Change(URN sportEventId, Date updateTime) implements FixtureChange {

        @Override
        public URN getSportEventId() {
            return sportEventId;
        }

        @Override
        public Date getUpdateTime() {
            return updateTime;
        }
    }
}
