package com.oddin.oddsfeedsdk.internal.session;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.OddsFeedSessionBuilder;
import com.oddin.oddsfeedsdk.ReplaySession;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Builds sessions as 0.0.x's builder did. {@link #build} needs an interest and a listener, and
 * forgets the interest and the events once it has built, so the next session sets its own; the
 * listener stays. The specific events accumulate until then, and make the interest {@code
 * SPECIFIED_MATCHES_ONLY}. {@link #buildReplay} needs only the listener, takes the interest {@code
 * ALL}, and forgets nothing.
 */
final class SessionBuilder implements OddsFeedSessionBuilder {

    private final SessionRegistry registry;
    private @Nullable OddsFeedListener listener;
    private @Nullable MessageInterest interest;
    private @Nullable Set<URN> events;

    SessionBuilder(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public OddsFeedSessionBuilder setListener(OddsFeedListener listener) {
        this.listener = requireNonNull(listener, "listener");
        return this;
    }

    @Override
    public OddsFeedSessionBuilder setMessageInterest(MessageInterest messageInterest) {
        this.interest = requireNonNull(messageInterest, "messageInterest");
        return this;
    }

    @Override
    public OddsFeedSessionBuilder setSpecificEventsOnly(Set<? extends URN> specificEvents) {
        requireNonNull(specificEvents, "specificEvents").forEach(event -> requireNonNull(event, "specificEvents"));
        interest = MessageInterest.SPECIFIED_MATCHES_ONLY;
        var events = this.events == null ? new LinkedHashSet<URN>() : this.events;
        events.addAll(specificEvents);
        this.events = events;
        return this;
    }

    @Override
    public OddsFeedSessionBuilder setSpecificEventOnly(URN specificEventOnly) {
        return setSpecificEventsOnly(Set.of(requireNonNull(specificEventOnly, "specificEventOnly")));
    }

    /**
     * @throws IllegalArgumentException without an interest or a listener, with 0.0.x's messages
     * @throws IllegalStateException once the feed is open
     */
    @Override
    public OddsFeedSession build() {
        var interest = this.interest;
        if (interest == null) {
            throw new IllegalArgumentException("Message interest not specified");
        }
        var session = registry.register(
                interest, events == null ? Set.of() : events, listener(), id -> new FeedSession(id, interest));
        this.interest = null;
        this.events = null;
        return session;
    }

    /**
     * @throws IllegalArgumentException without a listener, with 0.0.x's message
     * @throws IllegalStateException once the feed is open
     */
    @Override
    public ReplaySession buildReplay() {
        return registry.register(MessageInterest.ALL, Set.of(), listener(), ReplayFeedSession::new);
    }

    private OddsFeedListener listener() {
        var listener = this.listener;
        if (listener == null) {
            throw new IllegalArgumentException("Listener not specified");
        }
        return listener;
    }
}
