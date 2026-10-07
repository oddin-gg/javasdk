package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.*;

import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.Write;
import com.oddin.oddsfeedsdk.schema.rest.v1.RACompetitors;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASport;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportExtended;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATeam;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATeamExtended;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournament;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What the API's responses say about competitors, players, tournaments and sports, as writes to
 * their caches. As for a match, a value left out keeps what it had, unless the endpoint is the
 * field's authoritative one and always sends it.
 */
final class ProfileWrites {

    private ProfileWrites() {}

    /** A competitor profile's competitor; {@code players} is null when the profile had no list. */
    static Write competitor(RATeamExtended team, @Nullable List<RAPlayer> players, Locale locale) {
        Write write = team(Write.from(COMPETITOR_PROFILE, locale), team).put(ICON_PATH, team.getIconPath());
        return write.put(
                PLAYERS,
                players == null
                        ? null
                        : ids(players.stream().map(RAPlayer::getId).toList()));
    }

    /** A competitor as a match, a tournament or a schedule lists it. */
    static Write listedTeam(RATeam team, Locale locale) {
        return team(Write.from(TEAM_LISTED, locale), team);
    }

    /** A player profile's player. */
    static Write player(RAPlayer player, Locale locale) {
        return player(Write.from(PLAYER_PROFILE, locale), player).put(PLAYER_UNDERAGE, player.getUnderage());
    }

    /**
     * A player a competitor profile lists. Its underage is left out: as on 0.0.58, only the
     * player's own profile says it, so the list neither sets it nor keeps it set.
     */
    static Write listedPlayer(RAPlayer player, Locale locale) {
        return player(Write.from(PLAYER_LISTED, locale), player);
    }

    /**
     * A tournament, from its info or as listed. The competitors are its info's: those of the
     * tournament element, or, when the info sends none there, the info's own list.
     */
    static Write tournament(
            Endpoint endpoint, RATournament tournament, @Nullable RACompetitors competitors, Locale locale) {
        var length = tournament.getTournamentLength();
        var sport = tournament.getSport();
        return Write.from(endpoint, locale)
                .put(TOURNAMENT_NAME, tournament.getName())
                .put(TOURNAMENT_ABBREVIATION, tournament.getAbbreviation())
                .put(TOURNAMENT_SPORT_ID, sport == null ? null : ApiValues.urn(sport.getId()))
                .put(TOURNAMENT_SCHEDULED, ApiValues.instant(tournament.getScheduled()))
                .put(TOURNAMENT_SCHEDULED_END, ApiValues.instant(tournament.getScheduledEnd()))
                .put(START_DATE, length == null ? null : ApiValues.instant(length.getStartDate()))
                .put(END_DATE, length == null ? null : ApiValues.instant(length.getEndDate()))
                .put(RISK_TIER, tournament.getRiskTier())
                .put(
                        TOURNAMENT_COMPETITORS,
                        competitors == null
                                ? null
                                : ids(competitors.getCompetitor().stream()
                                        .map(RATeam::getId)
                                        .toList()));
    }

    /** A sport, from the sport list. */
    static Write sport(RASportExtended sport, Locale locale) {
        return listedSport(Write.from(SPORT_LIST, locale), sport).put(SPORT_ICON_PATH, sport.getIconPath());
    }

    /** A sport as a tournament or a profile names it. */
    static Write listedSport(RASport sport, Locale locale) {
        return listedSport(Write.from(SPORT_LISTED, locale), sport);
    }

    /**
     * A sport's tournaments, each once, in the order the API sent them. The old SDK's cache, the Go
     * SDK's and the .NET SDK drop a repeat too.
     */
    static Write sportTournaments(Collection<RATournament> tournaments, Locale locale) {
        var ids = ids(tournaments.stream().map(RATournament::getId).toList());
        return Write.from(SPORT_TOURNAMENT_LIST, locale)
                .put(SPORT_TOURNAMENTS, ids.stream().distinct().toList());
    }

    private static Write team(Write write, RATeam team) {
        return write.put(COMPETITOR_NAME, team.getName())
                .put(COMPETITOR_ABBREVIATION, team.getAbbreviation())
                .put(COUNTRY, team.getCountry())
                .put(COUNTRY_CODE, team.getCountryCode())
                .put(VIRTUAL, team.getVirtual())
                .put(UNDERAGE, team.getUnderage());
    }

    private static Write player(Write write, RAPlayer player) {
        return write.put(PLAYER_NAME, player.getName())
                .put(FULL_NAME, player.getFullName())
                .put(PLAYER_SPORT, player.getSport());
    }

    private static Write listedSport(Write write, RASport sport) {
        return write.put(SPORT_NAME, sport.getName()).put(SPORT_ABBREVIATION, sport.getAbbreviation());
    }

    /** The ids that are URNs, in their order. */
    private static List<URN> ids(List<String> ids) {
        var urns = new ArrayList<URN>(ids.size());
        for (String id : ids) {
            URN urn = ApiValues.urn(id);
            if (urn != null) {
                urns.add(urn);
            }
        }
        return List.copyOf(urns);
    }
}
