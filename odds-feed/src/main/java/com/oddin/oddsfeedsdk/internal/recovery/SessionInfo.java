package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.mq.MessageInterest;

/**
 * A session, as the recovery actor knows it. A replay session has none: a replay feed runs no
 * recovery, as in 0.0.x, so the safety net never sees one either.
 *
 * @param id the feed's own number for it, unique within the feed
 * @param takesSnapshotComplete whether its queue is bound to the snapshot completions, which
 *     decides whether a recovery waits for it: see {@code RoutingKeys.takesSnapshotComplete}
 */
public record SessionInfo(int id, MessageInterest interest, boolean takesSnapshotComplete) {}
