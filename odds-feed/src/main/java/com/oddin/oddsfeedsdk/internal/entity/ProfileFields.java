package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.Field;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The fields of a competitor, a player, a tournament and a sport, and the endpoints that write
 * them, as {@code CACHE-FIELDS.md} has them. Where the schema sends a field whenever it exists, an
 * authoritative response that leaves it out clears it; every other field it leaves out is kept.
 */
final class ProfileFields {

    // a competitor, by its profile
    static final Field<String> COMPETITOR_NAME = Field.localized("competitor name");
    static final Field<String> COMPETITOR_ABBREVIATION = Field.localized("competitor abbreviation");
    static final Field<String> COUNTRY = Field.localized("country");
    static final Field<String> COUNTRY_CODE = Field.shared("country code");
    static final Field<Boolean> VIRTUAL = Field.shared("virtual");
    static final Field<Integer> UNDERAGE = Field.shared("underage");
    static final Field<String> ICON_PATH = Field.shared("icon path");
    static final Field<List<URN>> PLAYERS = Field.shared("players");

    /** The competitor profile: name, abbreviation, underage and the player list are always sent. */
    static final Endpoint COMPETITOR_PROFILE = new Endpoint(
            "competitor profile",
            Set.of(
                    COMPETITOR_NAME,
                    COMPETITOR_ABBREVIATION,
                    COUNTRY,
                    COUNTRY_CODE,
                    VIRTUAL,
                    UNDERAGE,
                    ICON_PATH,
                    PLAYERS),
            Set.of(COMPETITOR_NAME, COMPETITOR_ABBREVIATION, UNDERAGE, PLAYERS));

    /** A competitor as a match, a tournament or a schedule lists it: it only fills. */
    static final Endpoint TEAM_LISTED = new Endpoint("competitor, as listed", Set.of(), Set.of());

    // a player, by its profile
    static final Field<String> PLAYER_NAME = Field.localized("player name");
    static final Field<String> FULL_NAME = Field.localized("full name");
    /** The sport's id as the player profile sends it: per locale in the API, the same in every one. */
    static final Field<String> PLAYER_SPORT = Field.shared("player sport");

    static final Field<Integer> PLAYER_UNDERAGE = Field.shared("player underage");

    /** The player profile: the name and the sport are always sent. */
    static final Endpoint PLAYER_PROFILE = new Endpoint(
            "player profile",
            Set.of(PLAYER_NAME, FULL_NAME, PLAYER_SPORT, PLAYER_UNDERAGE),
            Set.of(PLAYER_NAME, PLAYER_SPORT));

    /** A player as a competitor profile lists it: it only fills. */
    static final Endpoint PLAYER_LISTED = new Endpoint("player, as listed", Set.of(), Set.of());

    // a tournament, by its info
    static final Field<String> TOURNAMENT_NAME = Field.localized("tournament name");
    static final Field<String> TOURNAMENT_ABBREVIATION = Field.localized("tournament abbreviation");
    static final Field<URN> TOURNAMENT_SPORT_ID = Field.shared("tournament sport id");
    static final Field<Instant> TOURNAMENT_SCHEDULED = Field.shared("tournament scheduled time");
    static final Field<Instant> TOURNAMENT_SCHEDULED_END = Field.shared("tournament scheduled end time");
    static final Field<Instant> START_DATE = Field.shared("start date");
    static final Field<Instant> END_DATE = Field.shared("end date");
    static final Field<Integer> RISK_TIER = Field.shared("risk tier");
    static final Field<List<URN>> TOURNAMENT_COMPETITORS = Field.shared("tournament competitors");

    /** The tournament info: name, sport and risk tier are always sent. */
    static final Endpoint TOURNAMENT_INFO = new Endpoint(
            "tournament info",
            Set.of(
                    TOURNAMENT_NAME,
                    TOURNAMENT_ABBREVIATION,
                    TOURNAMENT_SPORT_ID,
                    TOURNAMENT_SCHEDULED,
                    TOURNAMENT_SCHEDULED_END,
                    START_DATE,
                    END_DATE,
                    RISK_TIER,
                    TOURNAMENT_COMPETITORS),
            Set.of(TOURNAMENT_NAME, TOURNAMENT_SPORT_ID, RISK_TIER));

    /** A tournament as a sport's list or a match lists it: it only fills. */
    static final Endpoint TOURNAMENT_LISTED = new Endpoint("tournament, as listed", Set.of(), Set.of());

    // a sport, by the sport list and by its tournaments
    static final Field<String> SPORT_NAME = Field.localized("sport name");
    static final Field<String> SPORT_ABBREVIATION = Field.localized("sport abbreviation");
    static final Field<String> SPORT_ICON_PATH = Field.shared("sport icon path");
    static final Field<List<URN>> SPORT_TOURNAMENTS = Field.shared("sport tournaments");

    /** The sport list: everything it sends of a sport is always sent. */
    static final Endpoint SPORT_LIST = new Endpoint(
            "sport list",
            Set.of(SPORT_NAME, SPORT_ABBREVIATION, SPORT_ICON_PATH),
            Set.of(SPORT_NAME, SPORT_ABBREVIATION, SPORT_ICON_PATH));

    /** A sport's tournaments: the one source of its tournament list, none of which need be there. */
    static final Endpoint SPORT_TOURNAMENT_LIST =
            new Endpoint("sport tournaments", Set.of(SPORT_TOURNAMENTS), Set.of());

    /** A sport as a tournament or a profile names it: it only fills. */
    static final Endpoint SPORT_LISTED = new Endpoint("sport, as listed", Set.of(), Set.of());

    private ProfileFields() {}
}
