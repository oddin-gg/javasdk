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
    /**
     * Per authoritative endpoint, the fetch - by the order fetches started in - that last wrote its
     * shared fields; none for an endpoint not written yet.
     */
    private final Map<Endpoint, Long> sharedFrom;
    /**
     * Per shared field, the fetch that last wrote or cleared it. A fetch that left the field out
     * and did not clear it has no say on it, so an older fetch that sent it still writes it.
     */
    private final Map<Slot, Long> slotFrom;

    private Entry(
            long generation,
            Map<Slot, Object> values,
            Set<Slot> authoritative,
            Map<Loaded, Instant> loaded,
            long changedAt,
            Map<Endpoint, Long> sharedFrom,
            Map<Slot, Long> slotFrom) {
        this.generation = generation;
        this.values = Map.copyOf(values);
        this.authoritative = Set.copyOf(authoritative);
        this.loaded = Map.copyOf(loaded);
        this.changedAt = changedAt;
        this.sharedFrom = Map.copyOf(sharedFrom);
        this.slotFrom = Map.copyOf(slotFrom);
    }

    /** An entry with nothing in it yet, of a generation no other entry of its cache has had. */
    static Entry empty(long generation) {
        return new Entry(generation, Map.of(), Set.of(), Map.of(), 0, Map.of(), Map.of());
    }

    /** The fetch that last wrote the endpoint's shared fields, by the order fetches started in; 0 for none. */
    long sharedFrom(Endpoint endpoint) {
        return sharedFrom.getOrDefault(endpoint, 0L);
    }

    private static final Entry NONE = empty(0);

    /** What a reader gets for a key its cache holds nothing of: no values, nothing loaded. */
    public static Entry none() {
        return NONE;
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
     * its shared fields in any locale, and marks them. A field it does not carry is cleared and
     * marked only when the endpoint always sends it when it exists - a localized one in its locale,
     * a shared one in any - since only then does its absence say it is gone; one the endpoint may
     * leave out is kept as it is. What it carries of fields another endpoint owns it writes by the
     * fill rule: only where absent and unmarked.
     *
     * <p>Two locales of one entity can be fetched at once: a response from a fetch that started
     * before the one that last wrote or cleared a shared field writes its locale's fields, but leaves
     * that newer shared one alone. A shared field the newer fetch left out, and did not clear, is
     * the older one's to write, as if the two had answered in the order they started. Another
     * endpoint's response is not in that race.
     *
     * @param fetch the fetch's place in the order fetches of this cache started in
     */
    Entry authoritative(Write write, Instant now, long ticks, long fetch) {
        Locale locale = write.locale();
        if (locale == null) {
            throw new IllegalArgumentException("an authoritative response from " + write.endpoint() + " has a locale");
        }
        var nextValues = new HashMap<>(values);
        var nextAuthoritative = new HashSet<>(authoritative);
        var owned = new HashSet<Slot>();
        var nextSlotFrom = new HashMap<>(slotFrom);
        // each field has one authoritative endpoint: only its own other responses race over it
        for (Field<?> field : write.endpoint().authoritativeFor()) {
            Slot slot = field.slot(locale);
            owned.add(slot);
            boolean shared = !field.isLocalized();
            if (shared && fetch < slotFrom.getOrDefault(slot, 0L)) {
                continue;
            }
            Object value = write.values().get(slot);
            if (value != null) {
                nextValues.put(slot, value);
                nextAuthoritative.add(slot);
            } else if (write.endpoint().alwaysSent().contains(field)
                    && !write.unsaid().contains(field)) {
                nextValues.remove(slot);
                nextAuthoritative.add(slot);
            } else {
                continue;
            }
            if (shared) {
                nextSlotFrom.put(slot, fetch);
            }
        }
        for (var carried : write.values().entrySet()) {
            Slot slot = carried.getKey();
            if (!owned.contains(slot) && !nextValues.containsKey(slot) && !nextAuthoritative.contains(slot)) {
                nextValues.put(slot, carried.getValue());
            }
        }
        var nextLoaded = new HashMap<>(loaded);
        nextLoaded.put(new Loaded(write.endpoint(), locale), now);
        var nextShared = new HashMap<>(sharedFrom);
        nextShared.merge(write.endpoint(), fetch, Math::max);
        return new Entry(generation, nextValues, nextAuthoritative, nextLoaded, ticks, nextShared, nextSlotFrom);
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
        return changed ? new Entry(generation, nextValues, authoritative, loaded, ticks, sharedFrom, slotFrom) : this;
    }

    /** The tombstone of this entry: nothing kept but a new generation. */
    Entry invalidated(long newGeneration, long ticks) {
        return new Entry(newGeneration, Map.of(), Set.of(), Map.of(), ticks, Map.of(), Map.of());
    }

    /** An endpoint fetched in a locale. */
    private record Loaded(Endpoint endpoint, Locale locale) {}
}
