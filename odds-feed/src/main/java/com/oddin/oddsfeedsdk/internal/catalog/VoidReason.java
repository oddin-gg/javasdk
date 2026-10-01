package com.oddin.oddsfeedsdk.internal.catalog;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Why a market was voided, as the API describes it. Immutable.
 *
 * @param template the message with its parameters left in, such as {@code Late by {minutes}
 *     minutes}, or null
 * @param params the names of its parameters, in order
 */
public record VoidReason(
        int id, String name, String description, @Nullable String template, List<String> params) {

    public VoidReason {
        params = List.copyOf(params);
    }
}
