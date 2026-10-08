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

    /**
     * The player in the default locale, as {@link #getPlayer(URN, Locale)} gives it. New in 1.0, as
     * the Go SDK has it.
     */
    default @Nullable Player getPlayer(URN id) {
        return null;
    }

    /**
     * The sport, in the default locale. Like a match, it loads nothing until a getter is called;
     * its name is read from the sport list, and a sport the list does not have fails its getters
     * by the exception strategy. New in 1.0, as the Go SDK has it.
     */
    default @Nullable Sport getSport(URN id) {
        return null;
    }

    /** The sport, in {@code locale}; see {@link #getSport(URN)}. New in 1.0. */
    default @Nullable Sport getSport(URN id, Locale locale) {
        return null;
    }

    /**
     * The tournament, in the default locale. Like a match, it loads nothing until a getter is
     * called; it is read from the tournament's info, its sport included, and a tournament the API
     * does not know fails its getters by the exception strategy. New in 1.0, as the Go SDK has it.
     */
    default @Nullable Tournament getTournament(URN id) {
        return null;
    }

    /** The tournament, in {@code locale}; see {@link #getTournament(URN)}. New in 1.0. */
    default @Nullable Tournament getTournament(URN id, Locale locale) {
        return null;
    }

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

    /**
     * Drops what is cached of the player in every locale; the next read loads it again. New in 1.0,
     * as the Go SDK has it.
     */
    default void clearPlayer(URN id) {}

    /**
     * Drops what is cached of the sport in every locale, its tournament list included; the next read
     * loads the sport list again. New in 1.0, as the Go SDK has it.
     */
    default void clearSport(URN id) {}
}
