package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.internal.cache.Field;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the public entities over the caches, and holds what they share: the caches, the exception
 * strategy, and the fan-out their getters load in.
 *
 * <p>An entity built here loads nothing until one of its getters is called, and every getter reads
 * the caches anew, so it sees what was loaded or written since. A getter given a locale loads that
 * locale; any other loads every locale of the entity, side by side. A getter of a match's or a
 * tournament's competitors, or of a competitor's players, loads them too, side by side, so its
 * reader waits for one round of loads rather than one per member. The getters run on the caller's
 * thread, and wait there.
 *
 * <p>A getter that cannot load what it reads follows the exception strategy: {@code THROW} throws the
 * first failure, {@code CATCH} logs it and returns null - a collection as a whole, never a part of
 * it.
 *
 * <p>Safe for concurrent use.
 */
public final class Entities {

    private static final Logger LOG = LoggerFactory.getLogger(Entities.class);

    final MatchCaches matches;
    final ProfileCaches profiles;
    final MatchStatusDescriptions statuses;
    private final ExceptionHandlingStrategy strategy;
    private final FanOut fanOut;

    /**
     * @param timeout the HTTP client timeout, each load's deadline
     * @param fetches where the loads of a fan-out wait: virtual threads
     * @param fanOutLimit how many loads of one getter run at once: the REST concurrency limit
     */
    public Entities(
            MatchCaches matches,
            ProfileCaches profiles,
            MatchStatusDescriptions statuses,
            ExceptionHandlingStrategy strategy,
            Duration timeout,
            Executor fetches,
            int fanOutLimit) {
        this.matches = matches;
        this.profiles = profiles;
        this.statuses = statuses;
        this.strategy = strategy;
        // a read loads twice at most, each load waiting its deadline and margin
        this.fanOut = new FanOut(
                fetches,
                fanOutLimit,
                timeout.plus(MatchCaches.MARGIN).multipliedBy(2).plus(MatchCaches.MARGIN));
    }

    /** The match, in {@code locales}: the first is the one a getter without a locale reads. */
    public Match match(URN id, List<Locale> locales) {
        return match(id, null, locales);
    }

    /**
     * The match, with the sport a message named it with: its sport id is then read from there, as
     * 0.0.x read it, rather than loaded.
     */
    public Match match(URN id, @Nullable URN sportId, List<Locale> locales) {
        return new MatchView(this, id, sportId, distinct(locales));
    }

    public Competitor competitor(URN id, List<Locale> locales) {
        return new CompetitorView(this, id, distinct(locales));
    }

    public Player player(URN id, List<Locale> locales) {
        return new PlayerView(this, id, distinct(locales));
    }

    /** The tournament; its sport is read from its info when {@code sportId} is null. */
    public Tournament tournament(URN id, @Nullable URN sportId, List<Locale> locales) {
        return new TournamentView(this, id, sportId, distinct(locales));
    }

    public Sport sport(URN id, List<Locale> locales) {
        return new SportView(this, id, distinct(locales));
    }

    /**
     * What the getter returns, by the exception strategy: under {@code THROW} its failure is thrown
     * as it is; under {@code CATCH} it is logged, and the getter returns null.
     */
    <T> @Nullable T guard(Object entity, Supplier<@Nullable T> getter) {
        try {
            return getter.get();
        } catch (RuntimeException e) {
            if (strategy == ExceptionHandlingStrategy.THROW) {
                throw e;
            }
            // under an outage every getter fails: a line each would drown the client's own log
            LOG.debug("{} could not be loaded; null under the CATCH strategy", entity, e);
            return null;
        }
    }

    /** What {@code load} returns for each item, loaded side by side; the first failure fails all. */
    <T, R> List<R> each(List<T> items, Function<? super T, ? extends R> load) {
        return fanOut.each(items, load);
    }

    /** Loads each of {@code members} in each of {@code locales}, side by side. */
    <T> void loadEach(List<T> members, List<Locale> locales, BiFunction<T, Locale, Entry> load) {
        var pairs = new ArrayList<InLocale<T>>(members.size() * locales.size());
        for (T member : members) {
            for (Locale locale : locales) {
                pairs.add(new InLocale<>(member, locale));
            }
        }
        each(pairs, pair -> load.apply(pair.member(), pair.locale()));
    }

    /** A localized field in each of {@code locales} that has it, in their order. */
    <T> Map<Locale, T> perLocale(List<Locale> locales, Function<Locale, Entry> load, Field<T> field) {
        List<Entry> entries = each(locales, load);
        var values = new LinkedHashMap<Locale, T>();
        for (int i = 0; i < locales.size(); i++) {
            Locale locale = locales.get(i);
            T value = entries.get(i).get(field, field.isLocalized() ? locale : null);
            if (value != null) {
                values.put(locale, value);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * The entry, if {@code endpoint} described the entity in {@code locale}: an entry it did not is an
     * entity the API does not know, or one invalidated again while it loaded.
     *
     * @throws ItemNotFoundException when it did not
     */
    static Entry found(Entry entry, Endpoint endpoint, Locale locale, Object entity) {
        if (entry.loadedAt(endpoint, locale) == null) {
            throw new ItemNotFoundException(entity + " not found in " + locale, null);
        }
        return entry;
    }

    static @Nullable Date date(@Nullable Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static List<Locale> distinct(List<Locale> locales) {
        if (locales.isEmpty()) {
            throw new IllegalArgumentException("an entity is read in one locale at least");
        }
        return List.copyOf(new LinkedHashSet<>(locales));
    }

    /** A member of a list in one locale: one load of a fan-out. */
    private record InLocale<T>(T member, Locale locale) {}
}
