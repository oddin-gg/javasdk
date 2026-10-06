package com.oddin.oddsfeedsdk.internal.message;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A value built when first asked for and then kept: of two first readers that both build it, the
 * one that stores first wins, and both return that one, so every reader sees the same value. A
 * build that throws stores nothing, and the next read builds again. Safe for concurrent use.
 */
final class Built<T> {

    private final Supplier<T> build;
    private final AtomicReference<@Nullable T> value = new AtomicReference<>();

    Built(Supplier<T> build) {
        this.build = build;
    }

    T get() {
        @Nullable T held = value.get();
        if (held != null) {
            return held;
        }
        // built outside any lock, so no reader waits for another's build
        T built = build.get();
        @Nullable T first = value.compareAndExchange(null, built);
        return first == null ? built : first;
    }
}
