package com.oddin.oddsfeedsdk.internal.cache;

import com.oddin.oddsfeedsdk.internal.cache.Field.Slot;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What one response or message says about one entity: the endpoint it came from, the locale it was
 * fetched in, and the values it carries. A field it does not put is a field it does not carry.
 */
public final class Write {

    private final Endpoint endpoint;
    private final @Nullable Locale locale;
    private final Map<Slot, Object> values = new LinkedHashMap<>();

    private Write(Endpoint endpoint, @Nullable Locale locale) {
        this.endpoint = endpoint;
        this.locale = locale;
    }

    /** From {@code endpoint}, fetched in {@code locale}; null for a source without one. */
    public static Write from(Endpoint endpoint, @Nullable Locale locale) {
        return new Write(endpoint, locale);
    }

    /** The field carries this value; a null value is the same as not putting it. */
    public <T> Write put(Field<T> field, @Nullable T value) {
        if (value != null) {
            values.put(field.slot(locale), value);
        }
        return this;
    }

    Endpoint endpoint() {
        return endpoint;
    }

    @Nullable
    Locale locale() {
        return locale;
    }

    Map<Slot, Object> values() {
        return values;
    }
}
