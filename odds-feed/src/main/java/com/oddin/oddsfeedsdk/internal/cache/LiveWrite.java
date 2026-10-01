package com.oddin.oddsfeedsdk.internal.cache;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What one live message or match summary says about an entity's live state: the values it carries,
 * and the fields it clears. A field it neither puts nor clears is kept as it is - within a feed
 * message a missing optional score means "keep what you have", never "reset".
 */
public final class LiveWrite {

    private final Map<Field<?>, Object> values = new LinkedHashMap<>();
    private final Set<Field<?>> cleared = new LinkedHashSet<>();

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

    /** The values once this write is applied to {@code before}. */
    Map<Field<?>, Object> applyTo(Map<Field<?>, Object> before) {
        var after = new HashMap<>(before);
        after.keySet().removeAll(cleared);
        after.putAll(values);
        return Map.copyOf(after);
    }

    private static void shared(Field<?> field) {
        if (field.isLocalized()) {
            throw new IllegalArgumentException(field + " has a value per locale; live state has none");
        }
    }
}
