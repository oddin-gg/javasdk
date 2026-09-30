package com.oddin.oddsfeedsdk.internal.cache;

import com.oddin.oddsfeedsdk.internal.cache.Field.Slot;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One cached entity at one moment: its values, which of them an authoritative endpoint wrote (or
 * cleared), when each authoritative endpoint was last fetched in each locale, and its generation.
 * Immutable; a write makes a new one.
 *
 * <p>An invalidated entry keeps its place with a new generation and nothing else - a tombstone - so
 * a fetch that started before the invalidation can tell and throw its result away.
 */
public final class Entry {

    private final long generation;
    private final Map<Slot, Object> values;
    private final Set<Slot> authoritative;
    private final Map<Loaded, Instant> loaded;
    private final long changedAt;

    private Entry(
            long generation,
            Map<Slot, Object> values,
            Set<Slot> authoritative,
            Map<Loaded, Instant> loaded,
            long changedAt) {
        this.generation = generation;
        this.values = Map.copyOf(values);
        this.authoritative = Set.copyOf(authoritative);
        this.loaded = Map.copyOf(loaded);
        this.changedAt = changedAt;
    }

    /** An entry with nothing in it yet, of a generation no other entry of its cache has had. */
    static Entry empty(long generation) {
        return new Entry(generation, Map.of(), Set.of(), Map.of(), 0);
    }

    /** When it last changed, on the cache's ticker: what its age counts from. */
    long changedAt() {
        return changedAt;
    }

    public long generation() {
        return generation;
    }

    /** The field's value in {@code locale}, or null; a shared field ignores the locale. */
    @SuppressWarnings("unchecked") // a slot holds only what a Write put for its field
    public <T> @Nullable T get(Field<T> field, @Nullable Locale locale) {
        return (T) values.get(field.slot(locale));
    }

    /** When {@code endpoint} was last fetched in {@code locale}, or null. */
    public @Nullable Instant loadedAt(Endpoint endpoint, Locale locale) {
        return loaded.get(new Loaded(endpoint, locale));
    }

    /**
     * Whether {@code endpoint} was fetched in {@code locale} within {@code age} of {@code now}. Each
     * authoritative endpoint of an entity has its own: a match's fixture fetched says nothing about
     * its summary.
     */
    public boolean isFresh(Endpoint endpoint, Locale locale, Instant now, Duration age) {
        Instant at = loaded.get(new Loaded(endpoint, locale));
        return at != null && !at.plus(age).isBefore(now);
    }

    /** Whether an authoritative endpoint wrote or cleared this field in {@code locale}. */
    public boolean isAuthoritative(Field<?> field, @Nullable Locale locale) {
        return authoritative.contains(field.slot(locale));
    }

    /**
     * An authoritative response: it replaces every field it is authoritative for in its locale, and
     * its shared fields in any locale, and marks them. A localized field it does not carry is
     * cleared; a shared one only when the endpoint always sends it, since two locales of one
     * response carry the same shared values.
     */
    Entry authoritative(Write write, Instant now, long ticks) {
        Locale locale = write.locale();
        if (locale == null) {
            throw new IllegalArgumentException("an authoritative response from " + write.endpoint() + " has a locale");
        }
        var nextValues = new HashMap<>(values);
        var nextAuthoritative = new HashSet<>(authoritative);
        for (Field<?> field : write.endpoint().authoritativeFor()) {
            Slot slot = field.slot(locale);
            Object value = write.values().get(slot);
            if (value != null) {
                nextValues.put(slot, value);
                nextAuthoritative.add(slot);
            } else if (field.isLocalized() || write.endpoint().alwaysSent().contains(field)) {
                nextValues.remove(slot);
                nextAuthoritative.add(slot);
            }
        }
        var nextLoaded = new HashMap<>(loaded);
        nextLoaded.put(new Loaded(write.endpoint(), locale), now);
        return new Entry(generation, nextValues, nextAuthoritative, nextLoaded, ticks);
    }

    /**
     * A response from another endpoint: it adds what is absent and was never written or cleared by
     * the authoritative endpoint, and nothing else. It marks no locale as loaded.
     */
    Entry fill(Write write, long ticks) {
        var nextValues = new HashMap<>(values);
        boolean changed = false;
        for (var value : write.values().entrySet()) {
            if (!nextValues.containsKey(value.getKey()) && !authoritative.contains(value.getKey())) {
                nextValues.put(value.getKey(), value.getValue());
                changed = true;
            }
        }
        return changed ? new Entry(generation, nextValues, authoritative, loaded, ticks) : this;
    }

    /** The tombstone of this entry: nothing kept but a new generation. */
    Entry invalidated(long newGeneration, long ticks) {
        return new Entry(newGeneration, Map.of(), Set.of(), Map.of(), ticks);
    }

    /** An endpoint fetched in a locale. */
    private record Loaded(Endpoint endpoint, Locale locale) {}
}
