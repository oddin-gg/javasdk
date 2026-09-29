package com.oddin.oddsfeedsdk.api;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.FixtureChange;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Sports, tournaments, matches, competitors and players. A method without a locale uses the default one. */
public interface SportsInfoManager {
    @Nullable
    List<Sport> getSports();

    @Nullable
    List<Sport> getSports(Locale locale);

    @Nullable
    List<Tournament> getActiveTournaments();

    @Nullable
    List<Tournament> getActiveTournaments(Locale locale);

    @Nullable
    List<Tournament> getActiveTournaments(String sportName);

    @Nullable
    List<Tournament> getActiveTournaments(String sportName, Locale locale);

    /** The matches scheduled on that date. */
    @Nullable
    List<Match> getMatchesFor(Date date);

    @Nullable
    List<Match> getMatchesFor(Date date, Locale locale);

    @Nullable
    List<Match> getLiveMatches();

    @Nullable
    List<Match> getLiveMatches(Locale locale);

    @Nullable
    Match getMatch(URN id);

    @Nullable
    Match getMatch(URN id, Locale locale);

    @Nullable
    Competitor getCompetitor(URN id);

    @Nullable
    Competitor getCompetitor(URN id, Locale locale);

    @Nullable
    Player getPlayer(URN id, Locale locale);

    /** The fixtures that changed in the last 24 hours. */
    @Nullable
    List<FixtureChange> getFixtureChanges();

    @Nullable
    List<FixtureChange> getFixtureChanges(Locale locale);

    /** Matches with prematch odds, a page at a time; {@code startIndex} is zero based. */
    @Nullable
    List<Match> getListOfMatches(int startIndex, int limit);

    @Nullable
    List<Match> getListOfMatches(int startIndex, int limit, Locale locale);

    @Nullable
    List<Tournament> getAvailableTournaments(URN sportId);

    @Nullable
    List<Tournament> getAvailableTournaments(URN sportId, Locale locale);

    void clearMatch(URN id);

    void clearTournament(URN id);

    void clearCompetitor(URN id);
}
