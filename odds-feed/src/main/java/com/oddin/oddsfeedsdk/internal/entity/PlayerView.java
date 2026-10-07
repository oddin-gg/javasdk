package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.FULL_NAME;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.PLAYER_NAME;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.PLAYER_PROFILE;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.PLAYER_SPORT;
import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.PLAYER_UNDERAGE;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.api.entities.sportevent.UnderageStatus;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A player as the client holds it: every getter a read of its profile. The sport id is per locale
 * in the API but the same in each, so every locale whose profile is loaded has it.
 */
final class PlayerView implements Player {

    private final Entities entities;
    private final URN id;
    private final List<Locale> locales;

    PlayerView(Entities entities, URN id, List<Locale> locales) {
        this.entities = entities;
        this.id = id;
        this.locales = locales;
    }

    @Override
    public URN getId() {
        return id;
    }

    @Override
    public @Nullable Map<Locale, String> getNames() {
        return entities.guard(this, () -> entities.perLocale(locales, this::profile, PLAYER_NAME));
    }

    @Override
    public @Nullable String getName(Locale locale) {
        return entities.guard(this, () -> profile(locale).get(PLAYER_NAME, locale));
    }

    @Override
    public @Nullable Map<Locale, String> getFullNames() {
        return entities.guard(this, () -> entities.perLocale(locales, this::profile, FULL_NAME));
    }

    @Override
    public @Nullable String getFullName(Locale locale) {
        return entities.guard(this, () -> profile(locale).get(FULL_NAME, locale));
    }

    @Override
    public @Nullable Map<Locale, String> getSportIDs() {
        return entities.guard(this, () -> entities.perLocale(locales, this::profile, PLAYER_SPORT));
    }

    @Override
    public @Nullable String getSportID(Locale locale) {
        return entities.guard(this, () -> profile(locale).get(PLAYER_SPORT, null));
    }

    /**
     * The value of the profiles in every locale of the player. A profile that leaves it out keeps
     * what an earlier one sent, as on 0.0.58: the field is not one the profile always sends.
     */
    @Override
    public @Nullable UnderageStatus getUnderage() {
        return entities.guard(this, () -> UnderageStatus.fromValue(shared().get(PLAYER_UNDERAGE, null)));
    }

    @Override
    public String toString() {
        return "player " + id;
    }

    private Entry profile(Locale locale) {
        return Entities.found(entities.profiles.player(id, locale, null), PLAYER_PROFILE, locale, this);
    }

    /** The profile in every locale of the player, for its shared fields. */
    private Entry shared() {
        return entities.each(locales, this::profile).getFirst();
    }
}
