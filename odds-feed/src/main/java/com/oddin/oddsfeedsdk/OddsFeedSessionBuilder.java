package com.oddin.oddsfeedsdk;

import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.util.Set;

/** Builds the sessions of one feed. */
public interface OddsFeedSessionBuilder {
    OddsFeedSessionBuilder setListener(OddsFeedListener listener);

    OddsFeedSessionBuilder setMessageInterest(MessageInterest messageInterest);

    OddsFeedSessionBuilder setSpecificEventsOnly(Set<? extends URN> specificEvents);

    OddsFeedSessionBuilder setSpecificEventOnly(URN specificEventOnly);

    OddsFeedSession build();

    ReplaySession buildReplay();
}
