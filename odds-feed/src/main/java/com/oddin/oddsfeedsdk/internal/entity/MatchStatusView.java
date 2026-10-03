package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.AWAY_SCORE;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.HOME_SCORE;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.MATCH_STATUS_ID;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.PERIOD_SCORES;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SCOREBOARD;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SCOREBOARD_AVAILABLE;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.STATUS;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SUMMARY;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.WINNER_ID;

import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.MatchStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Scoreboard;
import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.internal.cache.LiveState.LiveValues;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A match's status as the client holds it. The live getters read the match's live state - the
 * feed's while it is live, the summary's once it is quiet - and the winner the summary; each read
 * anew, so a getter inside a callback sees what the message wrote.
 *
 * <p>What the feed and the summary never sent reads as 0.0.x read it: no scores are 0, no period
 * scores an empty list, no scoreboard availability false.
 */
final class MatchStatusView implements MatchStatus {

    private final Entities entities;
    private final URN id;
    private final List<Locale> locales;

    MatchStatusView(Entities entities, URN id, List<Locale> locales) {
        this.entities = entities;
        this.id = id;
        this.locales = locales;
    }

    @Override
    public @Nullable List<PeriodScore> getPeriodScores() {
        return entities.guard(this, () -> {
            List<PeriodScore> periods = live().get(PERIOD_SCORES);
            return periods == null ? List.of() : periods;
        });
    }

    @Override
    public @Nullable Integer getMatchStatusId() {
        return entities.guard(this, () -> live().get(MATCH_STATUS_ID));
    }

    /** The match status, described in every locale of the match. */
    @Override
    public @Nullable LocalizedStaticData getMatchStatus() {
        return entities.guard(this, () -> describe(locales));
    }

    @Override
    public @Nullable LocalizedStaticData getMatchStatus(Locale locale) {
        return entities.guard(this, () -> describe(List.of(locale)));
    }

    @Override
    public @Nullable Double getHomeScore() {
        return entities.guard(this, () -> score(live().get(HOME_SCORE)));
    }

    @Override
    public @Nullable Double getAwayScore() {
        return entities.guard(this, () -> score(live().get(AWAY_SCORE)));
    }

    /** False under {@code CATCH} when the status cannot be loaded, as in 0.0.x. */
    @Override
    public boolean isScoreboardAvailable() {
        Boolean available = entities.guard(this, () -> Boolean.TRUE.equals(live().get(SCOREBOARD_AVAILABLE)));
        return available != null && available;
    }

    @Override
    public @Nullable Scoreboard getScoreboard() {
        return entities.guard(this, () -> live().get(SCOREBOARD));
    }

    /** The summary's, which is the one to retract it: the feed's winner is not taken. */
    @Override
    public @Nullable URN getWinnerId() {
        return entities.guard(this, () -> {
            Locale locale = locales.getFirst();
            return Entities.found(entities.matches.match(id, locale), SUMMARY, locale, this)
                    .get(WINNER_ID, null);
        });
    }

    @Override
    public @Nullable EventStatus getStatus() {
        return entities.guard(this, () -> live().get(STATUS));
    }

    /** Always empty, as in 0.0.x: nothing sends properties. */
    @Override
    public @Nullable Map<String, @Nullable Object> getProperties() {
        return entities.<Map<String, @Nullable Object>>guard(this, () -> {
            live();
            return Map.of();
        });
    }

    @Override
    public String toString() {
        return "status of match " + id;
    }

    private @Nullable LocalizedStaticData describe(List<Locale> in) {
        Integer statusId = live().get(MATCH_STATUS_ID);
        return statusId == null ? null : entities.statuses.describe(statusId, in);
    }

    /** The live state, its summary loaded again when it is out of date. */
    private LiveValues live() {
        LiveValues values = entities.matches.live(id);
        if (values == null) {
            throw new ItemNotFoundException(this + " not found", null);
        }
        return values;
    }

    private static Double score(@Nullable Double score) {
        return score == null ? 0.0 : score;
    }
}
