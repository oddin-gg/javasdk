package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeedsdk.api.BookmakerDetail;
import com.oddin.oddsfeedsdk.api.MarketDescriptionManager;
import com.oddin.oddsfeedsdk.api.SportsInfoManager;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;

/**
 * The SDK's entry point: one feed, its sessions and its managers.
 *
 * <p>This build declares the API only. The feed itself arrives with a later part of the rewrite;
 * until then every method but the configuration builder throws {@link UnsupportedOperationException}.
 */
// the methods throw only until the feed is implemented, so they are not ones to forbid calling
@SuppressWarnings("DoNotCallSuggester")
public final class OddsFeed {

    /**
     * Keeps {@code OddsFeed.Companion.getOddsFeedConfigurationBuilder()} compiling: 0.0.x was
     * Kotlin, and that is how Java code reached a function of its companion object.
     */
    @SuppressWarnings("VariableNameSameAsType") // the name is the compatibility
    public static final Companion Companion = new Companion();

    public OddsFeed(GlobalEventsListener listener, OddsFeedConfiguration configuration) {
        requireNonNull(listener, "listener");
        requireNonNull(configuration, "configuration");
    }

    public OddsFeed(
            GlobalEventsListener listener, OddsFeedConfiguration configuration, OddsFeedExtListener extListener) {
        this(listener, configuration);
        requireNonNull(extListener, "extListener");
    }

    public static OddsFeedConfigurationBuilder getOddsFeedConfigurationBuilder() {
        return new OddsFeedConfigurationBuilder();
    }

    public OddsFeedSessionBuilder getSessionBuilder() {
        throw notYet();
    }

    public MarketDescriptionManager getMarketDescriptionManager() {
        throw notYet();
    }

    public SportsInfoManager getSportsInfoManager() {
        throw notYet();
    }

    public ProducerManager getProducerManager() {
        throw notYet();
    }

    public BookmakerDetail getBookMakerDetail() {
        throw notYet();
    }

    public ReplayManager getReplayManager() {
        throw notYet();
    }

    public RecoveryManager getRecoveryManager() {
        throw notYet();
    }

    public void open() {
        throw notYet();
    }

    public void close() {
        throw notYet();
    }

    private static UnsupportedOperationException notYet() {
        return new UnsupportedOperationException("the feed is not implemented in this build yet");
    }

    /** Holds what 0.0.x's companion object had. */
    public static final class Companion {
        private Companion() {}

        public OddsFeedConfigurationBuilder getOddsFeedConfigurationBuilder() {
            return OddsFeed.getOddsFeedConfigurationBuilder();
        }
    }
}
