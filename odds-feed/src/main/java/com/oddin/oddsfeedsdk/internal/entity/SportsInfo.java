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

/**
 * The client's {@link SportsInfoManager}: the entities by id, the sports with their tournaments, and
 * the schedules, over the caches. An entity it returns loads nothing until a getter is called, as in
 * 0.0.x; a list loads what it needs to know its members - the sport list, a sport's tournament list,
 * a schedule - and what a schedule says of its matches fills the caches.
 *
 * <p>A method that cannot load what it needs follows the exception strategy, as an entity's getters
 * do: {@code THROW} throws, {@code CATCH} returns null - a list as a whole, never a part of it.
 *
 * <p>Safe for concurrent use.
 */
public final class SportsInfo implements SportsInfoManager {

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

    /** The sports of the sport list in {@code locale}, in its order. */
    @Override
    public @Nullable List<Sport> getSports(Locale locale) {
        return entities.guard("sports", () -> {
            var sports = new ArrayList<Sport>();
            for (URN id : entities.profiles.sports(locale, null)) {
                sports.add(entities.sport(id, List.of(locale)));
            }
            return List.copyOf(sports);
        });
    }

    @Override
    public @Nullable List<Tournament> getActiveTournaments() {
        return getActiveTournaments(defaultLocale);
    }

    /** The tournaments of every sport, each sport's list loaded side by side. */
    @Override
    public @Nullable List<Tournament> getActiveTournaments(Locale locale) {
        return entities.guard("active tournaments", () -> {
            List<URN> sports = entities.profiles.sports(locale, null);
            List<List<Tournament>> perSport = entities.each(sports, sport -> tournamentsOf(sport, locale));
            var tournaments = new ArrayList<Tournament>();
            perSport.forEach(tournaments::addAll);
            return List.copyOf(tournaments);
        });
    }

    @Override
    public @Nullable List<Tournament> getActiveTournaments(String sportName) {
        return getActiveTournaments(sportName, defaultLocale);
    }

    /**
     * The tournaments of the sport with this name in {@code locale}, whatever its case; none when the
     * sport list has no such sport, as in 0.0.x.
     */
    @Override
    public @Nullable List<Tournament> getActiveTournaments(String sportName, Locale locale) {
        return entities.guard("active tournaments of " + sportName, () -> {
            for (URN sport : entities.profiles.sports(locale, null)) {
                String name = entities.profiles.sport(sport, locale, null).get(SPORT_NAME, locale);
                if (sportName.equalsIgnoreCase(name)) {
                    return tournamentsOf(sport, locale);
                }
            }
            return List.of();
        });
    }

    @Override
    public @Nullable List<Match> getMatchesFor(Date date) {
        return getMatchesFor(date, defaultLocale);
    }

    /** The matches scheduled on that day, in UTC, as 0.0.x asked for them. */
    @Override
    public @Nullable List<Match> getMatchesFor(Date date, Locale locale) {
        var day = date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        return entities.guard("matches for " + day, () -> scheduled(locale, () -> client.fetchMatches(day, locale)));
    }

    @Override
    public @Nullable List<Match> getLiveMatches() {
        return getLiveMatches(defaultLocale);
    }

    @Override
    public @Nullable List<Match> getLiveMatches(Locale locale) {
        return entities.guard("live matches", () -> scheduled(locale, () -> client.fetchLiveMatches(locale)));
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
        return entities.guard("fixture changes", () -> {
            var changes = new ArrayList<FixtureChange>();
            for (RAFixtureChange change : client.fetchFixtureChanges(locale).getFixtureChange()) {
                URN id = ApiValues.urn(change.getSportEventId());
                Instant at = ApiValues.instant(change.getUpdateTime());
                if (id != null && at != null) {
                    changes.add(new Change(id, Date.from(at)));
                }
            }
            return List.copyOf(changes);
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
        return entities.guard(
                "list of matches", () -> scheduled(locale, () -> client.fetchSchedule(startIndex, limit, locale)));
    }

    @Override
    public @Nullable List<Tournament> getAvailableTournaments(URN sportId) {
        return getAvailableTournaments(sportId, defaultLocale);
    }

    @Override
    public @Nullable List<Tournament> getAvailableTournaments(URN sportId, Locale locale) {
        return entities.guard("tournaments of sport " + sportId, () -> tournamentsOf(sportId, locale));
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
        return List.copyOf(tournaments);
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
        return List.copyOf(matches);
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
