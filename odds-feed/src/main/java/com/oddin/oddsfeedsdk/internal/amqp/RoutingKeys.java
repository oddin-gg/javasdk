package com.oddin.oddsfeedsdk.internal.amqp;

import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The routing keys a session's queue is bound with, as 0.0.x bound them: its interest's keys, or
 * one per event for specified matches; with the node id, both the node's own messages and those for
 * every node; the snapshot completions of its node; and the alives.
 */
public final class RoutingKeys {

    /** The alives: what the SDK's own alive consumer binds. */
    public static final String ALIVE = "-.-.-.alive.#";

    private RoutingKeys() {}

    /**
     * The keys of one session.
     *
     * @param events the events of a specified-matches session; ignored for any other interest
     * @param withSnapshotComplete whether it takes snapshot completions: see {@link
     *     #takesSnapshotComplete}
     */
    public static List<String> forSession(
            MessageInterest interest, Collection<URN> events, @Nullable Integer nodeId, boolean withSnapshotComplete) {
        List<String> bases = interest == MessageInterest.SPECIFIED_MATCHES_ONLY
                ? events.stream()
                        .map(event -> "#." + event.getPrefix() + ":" + event.getType() + "." + event.getId())
                        .toList()
                : interest.getRoutingKeys();
        var keys = new ArrayList<String>();
        for (String base : bases) {
            if (nodeId == null) {
                keys.add(base + ".#");
            } else {
                keys.add(base + "." + nodeId + ".#");
                keys.add(base + ".-.#");
            }
        }
        if (withSnapshotComplete) {
            keys.add("-.-.-.snapshot_complete.-.-.-." + (nodeId == null ? "-" : nodeId));
        }
        if (interest != MessageInterest.SYSTEM_ALIVE_ONLY) {
            keys.add(ALIVE);
        }
        return List.copyOf(keys);
    }

    /**
     * Whether a session of {@code interest} takes snapshot completions among {@code all} the feed's
     * sessions: every one does, but for the low-priority one next to a high-priority one, which
     * would otherwise see each completion twice.
     */
    public static boolean takesSnapshotComplete(MessageInterest interest, Collection<MessageInterest> all) {
        return !(interest == MessageInterest.LOW_PRIORITY_ONLY && all.contains(MessageInterest.HI_PRIORITY_ONLY));
    }
}
