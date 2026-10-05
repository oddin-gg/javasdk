package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.cache.StaticData;
import com.oddin.oddsfeedsdk.mq.entities.MarketCancel;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A market of a bet cancel, with why it was cancelled. */
final class CancelMarket extends FeedMarket implements MarketCancel {

    private final @Nullable String voidReason;
    private final @Nullable Integer voidReasonId;
    private final @Nullable String voidReasonParams;

    CancelMarket(
            int id,
            Map<String, String> specifiers,
            MarketNames names,
            @Nullable Integer voidReason,
            @Nullable Integer voidReasonId,
            @Nullable String voidReasonParams) {
        super(id, specifiers, names);
        this.voidReason = voidReason == null ? null : voidReason.toString();
        this.voidReasonId = voidReasonId;
        this.voidReasonParams = voidReasonParams;
    }

    /** @deprecated use {@link #getVoidReasonId()} and {@link #getVoidReasonParams()}. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable StaticData getVoidReasonValue() {
        return null;
    }

    /**
     * The {@code void_reason} the market carries, null when it carries none. 0.0.x always returned
     * null (KD-5).
     *
     * @deprecated use {@link #getVoidReasonId()} and {@link #getVoidReasonParams()}.
     */
    @Deprecated
    @Override
    public @Nullable String getVoidReason() {
        return voidReason;
    }

    @Override
    public @Nullable Integer getVoidReasonId() {
        return voidReasonId;
    }

    @Override
    public @Nullable String getVoidReasonParams() {
        return voidReasonParams;
    }
}
