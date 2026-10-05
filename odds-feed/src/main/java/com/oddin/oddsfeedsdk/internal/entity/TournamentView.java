package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.END_DATE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.RISK_TIER;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.START_DATE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_ABBREVIATION;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_COMPETITORS;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_INFO;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_NAME;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_SCHEDULED;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_SCHEDULED_END;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.TOURNAMENT_SPORT_ID;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.LiveOddsAvailability;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportSummary;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A tournament as the client holds it: every getter a read of its info. Its sport is the one it was
 * built with, as in 0.0.x, or its info's when it was built without one.
 */
final class TournamentView implements Tournament {

    private final Entities entities;
    private final URN id;
    private final @Nullable URN sportId;
    private final List<Locale> locales;

    TournamentView(Entities entities, URN id, @Nullable URN sportId, List<Locale> locales) {
        this.entities = entities;
        this.id = id;
        this.sportId = sportId;
        this.locales = locales;
    }

    @Override
    public URN getId() {
        return id;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable URN getRefId() {
        return null;
    }

    @Override
    public @Nullable String getName(Locale locale) {
        return entities.guard(this, () -> info(locale).get(TOURNAMENT_NAME, locale));
    }

    @Override
    public @Nullable URN getSportId() {
        if (sportId != null) {
            return sportId;
        }
        return entities.guard(this, () -> shared().get(TOURNAMENT_SPORT_ID, null));
    }

    @Override
    public @Nullable Date getScheduledTime() {
        return entities.guard(this, () -> Entities.date(shared().get(TOURNAMENT_SCHEDULED, null)));
    }

    @Override
    public @Nullable Date getScheduledEndTime() {
        return entities.guard(this, () -> Entities.date(shared().get(TOURNAMENT_SCHEDULED_END, null)));
    }

    /** Never available: a tournament has no live odds of its own, as in 0.0.x. */
    @Override
    public LiveOddsAvailability getLiveOddsAvailability() {
        return LiveOddsAvailability.NOT_AVAILABLE;
    }

    /**
     * Every competitor its info lists, in its order, as 0.0.x listed them; their profiles are warmed
     * in every locale of the tournament first, side by side, and one that does not load is still
     * listed. An info listing none has none.
     */
    @Override
    public @Nullable List<Competitor> getCompetitors() {
        return entities.guard(this, () -> {
            List<URN> ids = shared().get(TOURNAMENT_COMPETITORS, null);
            List<URN> competitors = ids == null ? List.of() : ids;
            entities.warmEach(
                    competitors,
                    locales,
                    (competitor, locale) -> entities.profiles.competitor(competitor, locale, null));
            var list = new ArrayList<Competitor>(competitors.size());
            for (URN competitor : competitors) {
                list.add(new CompetitorView(entities, competitor, locales));
            }
            return list;
        });
    }

    @Override
    public @Nullable Date getStartDate() {
        return entities.guard(this, () -> Entities.date(shared().get(START_DATE, null)));
    }

    @Override
    public @Nullable Date getEndDate() {
        return entities.guard(this, () -> Entities.date(shared().get(END_DATE, null)));
    }

    @Override
    public @Nullable Integer getRiskTier() {
        return entities.guard(this, () -> shared().get(RISK_TIER, null));
    }

    @Override
    public @Nullable String getAbbreviation(Locale locale) {
        return entities.guard(this, () -> info(locale).get(TOURNAMENT_ABBREVIATION, locale));
    }

    /** Loads nothing when the tournament was built with its sport: the sport's getters do. */
    @Override
    public @Nullable SportSummary getSport() {
        URN sport = getSportId();
        return sport == null ? null : new SportView(entities, sport, locales);
    }

    @Override
    public String toString() {
        return "tournament " + id;
    }

    private Entry info(Locale locale) {
        return Entities.found(entities.profiles.tournament(id, locale, null), TOURNAMENT_INFO, locale, this);
    }

    /** The info in every locale of the tournament, for its shared fields. */
    private Entry shared() {
        return entities.each(locales, this::info).getFirst();
    }
}
