package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** What a match status id stands for, from the match status catalog: what a match's status reads as. */
@FunctionalInterface
public interface MatchStatusDescriptions {

    /**
     * The status described in each of {@code locales}, or null when the catalog does not have it.
     *
     * @throws RuntimeException when the catalog cannot be loaded, which the exception strategy then
     *     handles as any other failed load
     */
    @Nullable
    LocalizedStaticData describe(long id, List<Locale> locales);
}
