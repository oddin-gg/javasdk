package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a getter does when it cannot load what it returns, as the client's {@link
 * ExceptionHandlingStrategy} says: under {@code THROW} the failure reaches the caller, as the
 * exception 0.0.x threw there, under {@code CATCH} it is logged and the getter returns null. What
 * 0.0.x read from what it held, and so never failed, fails under neither.
 */
final class Strategy {

    private static final Logger LOG = LoggerFactory.getLogger(Strategy.class);

    private final ExceptionHandlingStrategy strategy;

    Strategy(ExceptionHandlingStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * What {@code read} returns; when it fails, under {@code THROW} an {@link ItemNotFoundException}
     * with the API's failure as its cause, the exception 0.0.x threw for a description it could not
     * load, or another failure as it is; under {@code CATCH} null.
     *
     * @param what what is read, such as {@code market description}, and {@code of} what, for the log
     */
    <T> @Nullable T read(Supplier<? extends @Nullable T> read, String what, Object of) {
        return call(
                () -> {
                    try {
                        return read.get();
                    } catch (ApiException failed) {
                        throw new ItemNotFoundException(
                                what + " " + of + " could not be loaded: " + failed.getMessage(), failed);
                    }
                },
                what,
                of);
    }

    /**
     * The same for a read 0.0.x let fail with the API's own exception, the void reasons: under
     * {@code THROW} its failure is thrown as it is.
     */
    <T> @Nullable T call(Supplier<? extends @Nullable T> call, String what, Object of) {
        try {
            return call.get();
        } catch (RuntimeException failed) {
            if (strategy == ExceptionHandlingStrategy.THROW) {
                throw failed;
            }
            LOG.debug("{} {} not read, null returned as the exception handling strategy is CATCH", what, of, failed);
            return null;
        }
    }

    /**
     * What {@code read} returns, null when it fails, under either strategy: for a getter that 0.0.x
     * answered from what it held, which never failed and was null when it held nothing.
     */
    static <T> @Nullable T quietly(Supplier<? extends @Nullable T> read, String what, Object of) {
        try {
            return read.get();
        } catch (RuntimeException failed) {
            LOG.debug("{} {} not read, null returned as 0.0.x returned it", what, of, failed);
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
