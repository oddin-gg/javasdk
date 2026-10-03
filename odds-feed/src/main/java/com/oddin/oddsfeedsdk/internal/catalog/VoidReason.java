package com.oddin.oddsfeedsdk.internal.catalog;

import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Why a market was voided, as the API describes it; the client's {@link MarketVoidReason} as it is.
 * Immutable.
 *
 * @param template the message with its parameters left in, such as {@code Late by {minutes}
 *     minutes}, or null
 * @param params the names of its parameters, in order
 */
public record VoidReason(
        int id, String name, String description, @Nullable String template, List<String> params)
        implements MarketVoidReason {

    public VoidReason {
        params = List.copyOf(params);
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public @Nullable String getTemplate() {
        return template;
    }

    @Override
    public List<String> getParams() {
        return params;
    }
}
