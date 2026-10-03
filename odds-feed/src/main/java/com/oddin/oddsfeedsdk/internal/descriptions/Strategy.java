package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a getter does when it cannot load what it returns, as the client's {@link
 * ExceptionHandlingStrategy} says: under {@code THROW} the failure reaches the caller, under {@code
 * CATCH} it is logged and the getter returns null.
 */
final class Strategy {

    private static final Logger LOG = LoggerFactory.getLogger(Strategy.class);

    private final ExceptionHandlingStrategy strategy;

    Strategy(ExceptionHandlingStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * What {@code read} returns; when it fails, the failure under {@code THROW} and null under {@code
     * CATCH}.
     *
     * @param what what is read, such as {@code market description}, and {@code of} what, for the log
     */
    <T> @Nullable T read(Supplier<? extends @Nullable T> read, String what, Object of) {
        try {
            return read.get();
        } catch (RuntimeException failed) {
            if (strategy == ExceptionHandlingStrategy.THROW) {
                throw failed;
            }
            LOG.debug("{} {} not read, null returned as the exception handling strategy is CATCH", what, of, failed);
            return null;
        }
    }

    /**
     * {@code value}, which a getter must find: when it is null, an {@link ItemNotFoundException} under
     * {@code THROW}, as in 0.0.x, and null under {@code CATCH}.
     *
     * @param what what is looked for, {@code of} what and {@code in} what, for the exception's message
     */
    <T> @Nullable T found(@Nullable T value, String what, Object of, Object in) {
        if (value == null && strategy == ExceptionHandlingStrategy.THROW) {
            throw new ItemNotFoundException(what + " " + of + " not found in " + in, null);
        }
        return value;
    }
}
