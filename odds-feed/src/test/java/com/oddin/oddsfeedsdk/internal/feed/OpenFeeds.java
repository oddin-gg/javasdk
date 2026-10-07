package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.internal.recovery.RecoveryActor;
import org.jspecify.annotations.Nullable;

/** For tests in other packages: what an open feed built. */
public final class OpenFeeds {

    private OpenFeeds() {}

    /** The feed's recovery actor, null for a replay feed. */
    public static @Nullable RecoveryActor actor(OpenFeed feed) {
        return feed.actor();
    }
}
