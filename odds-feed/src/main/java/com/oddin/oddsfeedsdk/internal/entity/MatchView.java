package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.COMPETITORS;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.EXTRA_INFO;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.LIVE_ODDS;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.NAME;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SCHEDULED;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SCHEDULED_END;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SPORT_ID;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.SUMMARY;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.TOURNAMENT_ID;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Fixture;
import com.oddin.oddsfeedsdk.api.entities.sportevent.LiveOddsAvailability;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.MatchStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportFormat;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TeamCompetitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.internal.entity.MatchFields.CompetitorRef;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A match as the client holds it: its id and locales, and every getter a read of the caches. The
 * live getters read the match's live state, through {@link #getStatus()}; the rest its summary.
 */
final class MatchView implements Match {

    /** The {@code extra_info} key the sport format is sent under. */
    static final String SPORT_FORMAT = "sport_format";

    private static final String HOME = "home";
    private static final String AWAY = "away";

    private final Entities entities;
    private final URN id;
    private final @Nullable URN sportId;
    private final List<Locale> locales;

    MatchView(Entities entities, URN id, @Nullable URN sportId, List<Locale> locales) {
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
        return entities.guard(this, () -> summary(locale).get(NAME, locale));
    }

    @Override
    public @Nullable URN getSportId() {
        if (sportId != null) {
            return sportId;
        }
        return entities.guard(this, () -> shared().get(SPORT_ID, null));
    }

    @Override
    public @Nullable Date getScheduledTime() {
        return entities.guard(this, () -> Entities.date(shared().get(SCHEDULED, null)));
    }

    @Override
    public @Nullable Date getScheduledEndTime() {
        return entities.guard(this, () -> Entities.date(shared().get(SCHEDULED_END, null)));
    }

    /** A match the summary sends without {@code liveodds} has them available, as 0.0.x read it. */
    @Override
    public @Nullable LiveOddsAvailability getLiveOddsAvailability() {
        return entities.guard(this, () -> LiveOddsAvailability.fromApiEvent(shared().get(LIVE_ODDS, null)));
    }

    /** Loads nothing: its getters do, as in 0.0.x. */
    @Override
    public MatchStatus getStatus() {
        return new MatchStatusView(entities, id, locales);
    }

    /**
     * Every competitor the summary lists, in its order, as 0.0.x listed them; their profiles are
     * warmed in every locale of the match first, side by side, and one that does not load is still
     * listed. A match that cannot be loaded has none under {@code CATCH}, as in 0.0.x.
     */
    @Override
    public @Nullable List<Competitor> getCompetitors() {
        List<Competitor> listed = entities.guard(this, () -> {
            List<CompetitorRef> refs = refs(shared());
            entities.warmEach(refs, locales, (ref, locale) -> entities.profiles.competitor(ref.id(), locale, null));
            var competitors = new ArrayList<Competitor>(refs.size());
            for (CompetitorRef ref : refs) {
                competitors.add(new TeamCompetitorView(entities, ref.id(), ref.qualifier(), locales));
            }
            return competitors;
        });
        return listed == null ? new ArrayList<>() : listed;
    }

    @Override
    public @Nullable Tournament getTournament() {
        return entities.guard(this, () -> {
            Entry match = shared();
            URN tournament = match.get(TOURNAMENT_ID, null);
            if (tournament == null) {
                return null;
            }
            return entities.tournament(tournament, sportId != null ? sportId : match.get(SPORT_ID, null), locales);
        });
    }

    @Override
    public @Nullable TeamCompetitor getHomeCompetitor() {
        return entities.guard(this, () -> side(HOME));
    }

    @Override
    public @Nullable TeamCompetitor getAwayCompetitor() {
        return entities.guard(this, () -> side(AWAY));
    }

    /** Loads nothing: its getters do, as in 0.0.x. */
    @Override
    public Fixture getFixture() {
        return new FixtureView(entities, id);
    }

    @Override
    public @Nullable SportFormat getSportFormat() {
        return entities.guard(this, () -> sportFormat(shared().get(EXTRA_INFO, null)));
    }

    @Override
    public @Nullable Map<String, String> getExtraInfo() {
        return entities.guard(this, () -> {
            Map<String, String> info = shared().get(EXTRA_INFO, null);
            return info == null ? null : new LinkedHashMap<>(info);
        });
    }

    @Override
    public String toString() {
        return "match " + id;
    }

    /**
     * The format the extra info names: classic when it names none, as 0.0.x read it, and unknown for
     * a value the enum does not have, such as {@code esports}.
     */
    static SportFormat sportFormat(@Nullable Map<String, String> extraInfo) {
        String value = extraInfo == null ? null : extraInfo.get(SPORT_FORMAT);
        if (value == null) {
            return SportFormat.CLASSIC;
        }
        for (SportFormat format : SportFormat.values()) {
            if (format.getValue().equals(value)) {
                return format;
            }
        }
        return SportFormat.UNKNOWN;
    }

    /**
     * The competitor the summary qualifies as {@code qualifier}, of a match of two, one home and one
     * away - whatever their order, and whatever the sport format but a race, which has neither. 0.0.x
     * took the first and the second of a classic match, and failed for any other format, which left
     * every esports match without them.
     */
    private @Nullable TeamCompetitor side(String qualifier) {
        Entry match = shared();
        if (sportFormat(match.get(EXTRA_INFO, null)) == SportFormat.RACE) {
            return null;
        }
        List<CompetitorRef> refs = refs(match);
        CompetitorRef home = null;
        CompetitorRef away = null;
        for (CompetitorRef ref : refs) {
            if (HOME.equals(ref.qualifier())) {
                home = ref;
            } else if (AWAY.equals(ref.qualifier())) {
                away = ref;
            }
        }
        if (refs.size() != 2 || home == null || away == null) {
            return null;
        }
        CompetitorRef side = HOME.equals(qualifier) ? home : away;
        return new TeamCompetitorView(entities, side.id(), side.qualifier(), locales);
    }

    private static List<CompetitorRef> refs(Entry match) {
        List<CompetitorRef> refs = match.get(COMPETITORS, null);
        return refs == null ? List.of() : refs;
    }

    /** The summary in {@code locale}. */
    private Entry summary(Locale locale) {
        return Entities.found(entities.matches.match(id, locale), SUMMARY, locale, this);
    }

    /** The summary in every locale of the match, for its shared fields. */
    private Entry shared() {
        return entities.each(locales, this::summary).getFirst();
    }
}
