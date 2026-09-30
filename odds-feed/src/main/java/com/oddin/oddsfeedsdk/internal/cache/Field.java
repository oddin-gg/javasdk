package com.oddin.oddsfeedsdk.internal.cache;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One field of a cached entity: its name, and whether it has a value per locale. Fields are
 * compared by identity, so each entity declares its own as constants.
 *
 * @param <T> the type of its value
 */
@SuppressWarnings("UnusedTypeParameter") // it types what Write.put and Entry.get take for the field
public final class Field<T> {

    private final String name;
    private final boolean localized;

    private Field(String name, boolean localized) {
        this.name = name;
        this.localized = localized;
    }

    /** A field with a value per locale, such as a name. */
    public static <T> Field<T> localized(String name) {
        return new Field<>(name, true);
    }

    /** A field with one value whatever the locale, such as a start time. */
    public static <T> Field<T> shared(String name) {
        return new Field<>(name, false);
    }

    public String name() {
        return name;
    }

    public boolean isLocalized() {
        return localized;
    }

    /** Where this field's value for {@code locale} is kept; a shared field ignores the locale. */
    Slot slot(@Nullable Locale locale) {
        if (localized && locale == null) {
            throw new IllegalArgumentException(name + " has a value per locale; which one?");
        }
        return new Slot(this, localized ? locale : null);
    }

    @Override
    public String toString() {
        return name;
    }

    /** A field in a locale, or a shared field. */
    record Slot(Field<?> field, @Nullable Locale locale) {}
}
