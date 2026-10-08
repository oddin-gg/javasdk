package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_ABBREVIATION;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_ICON_PATH;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_LIST;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_NAME;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_TOURNAMENTS;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.SPORT_TOURNAMENT_LIST;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A sport as the client holds it: its names and icon from the sport list of each locale, and its
 * tournaments from its own tournament list. The tournaments load nothing until read: a sport can
 * have hundreds.
 */
final class SportView implements Sport {

    private final Entities entities;
    private final URN id;
    private final List<Locale> locales;
    /**
     * Whether only the sport list's word counts: a sport asked for by id is one the list names, where
     * a tournament's or a competitor's sport may be one only they named.
     */
    private final boolean listedOnly;

    SportView(Entities entities, URN id, List<Locale> locales) {
        this(entities, id, locales, false);
    }

    SportView(Entities entities, URN id, List<Locale> locales, boolean listedOnly) {
        this.entities = entities;
        this.id = id;
        this.locales = locales;
        this.listedOnly = listedOnly;
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
    public @Nullable Map<Locale, String> getNames() {
        return entities.guard(this, () -> entities.perLocale(locales, this::listed, SPORT_NAME));
    }

    @Override
    public @Nullable String getName(Locale locale) {
        return entities.guard(this, () -> listed(locale).get(SPORT_NAME, locale));
    }

    @Override
    public @Nullable String getAbbreviation(Locale locale) {
        return entities.guard(this, () -> listed(locale).get(SPORT_ABBREVIATION, locale));
    }

    @Override
    public @Nullable String getIconPath(Locale locale) {
        return entities.guard(this, () -> listed(locale).get(SPORT_ICON_PATH, null));
    }

    /**
     * The tournaments of its list in the first locale of the sport: the list says nothing in another
     * locale that a tournament does not load itself.
     */
    @Override
    public @Nullable List<Tournament> getTournaments() {
        return entities.guard(this, () -> {
            Locale locale = locales.getFirst();
            List<URN> ids = Entities.found(
                            entities.profiles.sportTournaments(id, locale, null), SPORT_TOURNAMENT_LIST, locale, this)
                    .get(SPORT_TOURNAMENTS, null);
            var tournaments = new ArrayList<Tournament>();
            for (URN tournament : ids == null ? List.<URN>of() : ids) {
                tournaments.add(new TournamentView(entities, tournament, id, locales));
            }
            return tournaments;
        });
    }

    @Override
    public String toString() {
        return "sport " + id;
    }

    /**
     * The sport as the list in {@code locale} describes it; one the list does not name is not found,
     * unless a tournament or a profile has named it there and only the list's word counts.
     */
    private Entry listed(Locale locale) {
        Entry sport = entities.profiles.sport(id, locale, null);
        if (sport.loadedAt(SPORT_LIST, locale) == null && (listedOnly || sport.get(SPORT_NAME, locale) == null)) {
            throw new ItemNotFoundException(this + " not found in " + locale, null);
        }
        return sport;
    }
}
