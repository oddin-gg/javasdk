package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.internal.catalog.MarketDescriptions;
import com.oddin.oddsfeedsdk.internal.entity.Entities;
import java.util.Locale;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the names of a message's markets and outcomes are read from, and how a name that cannot be
 * read is answered: under {@code THROW} an {@link ItemNotFoundException}, with the API's failure as
 * its cause when what it is read from could not be loaded, and another failure as it is, as the
 * entity getters answer; under {@code CATCH} null, as in 0.0.x.
 *
 * @param defaultLocale the locale a name is read in when none is given
 */
record Naming(MarketDescriptions catalog, Entities entities, ExceptionHandlingStrategy strategy, Locale defaultLocale) {

    private static final Logger LOG = LoggerFactory.getLogger(Naming.class);

    /**
     * The name {@code read} gives.
     *
     * @param what {@code market name} or {@code outcome name}, for 0.0.x's exception message
     * @throws ItemNotFoundException under {@code THROW}, when it gives none or what it reads could not
     *     be loaded
     */
    @Nullable
    String name(Supplier<@Nullable String> read, String what, Object of) {
        String name;
        try {
            name = read.get();
        } catch (RuntimeException failed) {
            if (strategy == ExceptionHandlingStrategy.THROW) {
                // what could not be loaded is not found, as the entity getters say it, with why
                throw failed instanceof ApiException notLoaded
                        ? new ItemNotFoundException("Cannot find " + what, notLoaded)
                        : failed;
            }
            LOG.debug("The {} of {} could not be read; null under the CATCH strategy", what, of, failed);
            return null;
        }
        if (name == null && strategy == ExceptionHandlingStrategy.THROW) {
            throw new ItemNotFoundException("Cannot find " + what, null);
        }
        return name;
    }
}
