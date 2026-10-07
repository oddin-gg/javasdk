package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.COMPETITOR_ABBREVIATION;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.COMPETITOR_NAME;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.COMPETITOR_PROFILE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.COUNTRY;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.COUNTRY_CODE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.ICON_PATH;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.PLAYERS;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.UNDERAGE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.VIRTUAL;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A competitor as the client holds it: its id and locales, and every getter a read of its profile.
 * A getter of every locale - the names, the countries - loads them side by side, and has a value
 * for each locale whose profile has one.
 */
class CompetitorView implements Competitor {

    private final Entities entities;
    private final URN id;
    private final List<Locale> locales;

    CompetitorView(Entities entities, URN id, List<Locale> locales) {
        this.entities = entities;
        this.id = id;
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
    public @Nullable Map<Locale, String> getNames() {
        return entities.guard(this, () -> entities.perLocale(locales, this::profile, COMPETITOR_NAME));
    }

    @Override
    public @Nullable String getName(Locale locale) {
        return entities.guard(this, () -> profile(locale).get(COMPETITOR_NAME, locale));
    }

    @Override
    public @Nullable Map<Locale, String> getCountries() {
        return entities.guard(this, () -> entities.perLocale(locales, this::profile, COUNTRY));
    }

    @Override
    public @Nullable Map<Locale, String> getAbbreviations() {
        return entities.guard(this, () -> entities.perLocale(locales, this::profile, COMPETITOR_ABBREVIATION));
    }

    @Override
    public @Nullable Boolean getVirtual() {
        return entities.guard(this, () -> shared().get(VIRTUAL, null));
    }

    @Override
    public @Nullable String getCountryCode() {
        return entities.guard(this, () -> shared().get(COUNTRY_CODE, null));
    }

    /** @deprecated the number as the API sends it; {@link #getUnderageStatus()} reads it. */
    @Deprecated
    @Override
    public @Nullable Integer getUnderage() {
        return entities.guard(this, () -> shared().get(UNDERAGE, null));
    }

    @Override
    public @Nullable String getIconPath() {
        return entities.guard(this, () -> shared().get(ICON_PATH, null));
    }

    @Override
    public @Nullable String getCountry(Locale locale) {
        return entities.guard(this, () -> profile(locale).get(COUNTRY, locale));
    }

    @Override
    public @Nullable String getAbbreviation(Locale locale) {
        return entities.guard(this, () -> profile(locale).get(COMPETITOR_ABBREVIATION, locale));
    }

    /**
     * Every player the profile lists, in its order, as 0.0.x listed them, at once: their profiles
     * start loading in every locale of the competitor in the background, and one that does not load
     * is still listed. A profile listing none has none: unlike 0.0.x, an empty list is not loaded again on
     * every call.
     */
    @Override
    public @Nullable List<@Nullable Player> getPlayers() {
        return entities.<List<@Nullable Player>>guard(this, () -> {
            List<URN> ids = shared().get(PLAYERS, null);
            List<URN> players = ids == null ? List.of() : ids;
            entities.warmEach(
                    players,
                    locales,
                    (player, locale) -> entities.profiles.playerWarm(player, locale),
                    (player, locale, deadline) -> entities.profiles.player(player, locale, deadline));
            var list = new ArrayList<@Nullable Player>(players.size());
            for (URN player : players) {
                list.add(new PlayerView(entities, player, locales));
            }
            return list;
        });
    }

    @Override
    public String toString() {
        return "competitor " + id;
    }

    private Entry profile(Locale locale) {
        return Entities.found(entities.profiles.competitor(id, locale, null), COMPETITOR_PROFILE, locale, this);
    }

    /** The profile in every locale of the competitor, for its shared fields. */
    private Entry shared() {
        return entities.each(locales, this::profile).getFirst();
    }
}
