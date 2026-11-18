package com.oddin.oddsfeedsdk.internal.cache;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * What one live message or match summary says about an entity's live state: the values it carries,
 * and the fields it clears. A field it neither puts nor clears is kept as it is - within a feed
 * message a missing optional score means "keep what you have", never "reset". A field it amends is
 * kept with a part of it changed.
 */
public final class LiveWrite {

    private final Map<Field<?>, Object> values = new LinkedHashMap<>();
    private final Set<Field<?>> cleared = new LinkedHashSet<>();
    private final Map<Field<?>, UnaryOperator<Object>> amended = new LinkedHashMap<>();

    private LiveWrite() {}

    public static LiveWrite of() {
        return new LiveWrite();
    }

    /** The field carries this value; a null value is the same as not putting it. */
    public <T> LiveWrite put(Field<T> field, @Nullable T value) {
        shared(field);
        if (value != null) {
            values.put(field, value);
            cleared.remove(field);
        }
        return this;
    }

    /** The field has no value any more, such as a match clock once the match is over. */
    public LiveWrite clear(Field<?> field) {
        shared(field);
        values.remove(field);
        cleared.add(field);
        return this;
    }

    /**
     * The field keeps the value it holds, changed by {@code change}; it stays without one if it holds
     * none. A value this write puts, or a clear of it, takes the place of the change. The change
     * returns a value, never null: that is {@link #clear}.
     */
    public <T> LiveWrite amend(Field<T> field, UnaryOperator<T> change) {
        shared(field);
        @SuppressWarnings("unchecked") // a field holds only what a LiveWrite put for it
        UnaryOperator<Object> untyped = value -> change.apply((T) value);
        amended.put(field, untyped);
        return this;
    }

    /** The values once this write is applied to {@code before}. */
    Map<Field<?>, Object> applyTo(Map<Field<?>, Object> before) {
        var after = new HashMap<>(before);
        after.keySet().removeAll(cleared);
        amended.forEach((field, change) -> after.computeIfPresent(field, (key, value) -> change.apply(value)));
        after.putAll(values);
        return Map.copyOf(after);
    }

    /** The values once this write fills {@code before}: only the fields it has none for, clearing nothing. */
    Map<Field<?>, Object> fill(Map<Field<?>, Object> before) {
        var after = new HashMap<>(before);
        values.forEach(after::putIfAbsent);
        return Map.copyOf(after);
    }

    private static void shared(Field<?> field) {
        if (field.isLocalized()) {
            throw new IllegalArgumentException(field + " has a value per locale; live state has none");
        }
    }
}
