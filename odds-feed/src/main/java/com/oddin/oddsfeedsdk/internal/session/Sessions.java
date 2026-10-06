package com.oddin.oddsfeedsdk.internal.session;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.exceptions.UnsupportedMessageInterestCombination;
import com.oddin.oddsfeedsdk.internal.amqp.RoutingKeys;
import com.oddin.oddsfeedsdk.internal.recovery.SessionInfo;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What {@code open()} does with the feed's sessions, worked out before it does any of it, as 0.0.x
 * worked it out: whether their interests combine, the keys each session's queue is bound with,
 * which of them take snapshot completions, and the producers no session asks for, which the feed
 * disables. Pure: it reads what it is given and touches nothing.
 */
public final class Sessions {

    private Sessions() {}

    /**
     * The plan for {@code sessions}, in their order.
     *
     * @param available the producers the API lists, by id
     * @param nodeId the configured SDK node id, if any
     * @throws UnsupportedMessageInterestCombination when the interests do not combine, with 0.0.x's
     *     message: see {@link #validate}
     */
    public static Plan plan(
            List<SessionSpec> sessions, Map<Long, ? extends Producer> available, @Nullable Integer nodeId) {
        if (sessions.isEmpty()) {
            // the registry refuses to open a feed without sessions, with 0.0.x's message
            throw new IllegalArgumentException("no sessions to plan");
        }
        var interests = sessions.stream().map(SessionSpec::interest).toList();
        validate(interests);
        var planned = sessions.stream()
                .map(session -> {
                    var completions = takesSnapshotComplete(session, interests);
                    return new Planned(
                            session,
                            RoutingKeys.forSession(session.interest(), session.events(), nodeId, completions),
                            completions);
                })
                .toList();
        return new Plan(planned, unrequested(interests, available));
    }

    /**
     * Whether the interests of a feed's sessions combine, in 0.0.x's words and order: one session
     * may have any interest; of several, no two may share one, none may be {@code ALL} - which a
     * replay session has, so it is always alone - and the priority interests do not mix with the
     * live and prematch ones.
     */
    static void validate(List<MessageInterest> interests) {
        if (interests.size() == 1) {
            return;
        }
        if (Set.copyOf(interests).size() != interests.size()) {
            throw new UnsupportedMessageInterestCombination("found duplicate message interests", null);
        }
        if (interests.contains(MessageInterest.ALL)) {
            throw new UnsupportedMessageInterestCombination(
                    "all messages can be used only for single session configuration", null);
        }
        var priority = interests.contains(MessageInterest.HI_PRIORITY_ONLY)
                || interests.contains(MessageInterest.LOW_PRIORITY_ONLY);
        var scoped = interests.contains(MessageInterest.PREMATCH_ONLY) || interests.contains(MessageInterest.LIVE_ONLY);
        if (priority && scoped) {
            throw new UnsupportedMessageInterestCombination("cannot combine priority messages with other types", null);
        }
    }

    /**
     * Whether a session takes snapshot completions: as {@link RoutingKeys#takesSnapshotComplete}
     * says, but for a specified-matches session without events, which 0.0.x bound to nothing but
     * the alives, since it bound the completions once per key of the session's own.
     */
    static boolean takesSnapshotComplete(SessionSpec session, List<MessageInterest> all) {
        return RoutingKeys.takesSnapshotComplete(session.interest(), all)
                && !(session.interest() == MessageInterest.SPECIFIED_MATCHES_ONLY
                        && session.events().isEmpty());
    }

    /** The producers no interest can receive messages from, in id order. */
    static Set<Long> unrequested(List<MessageInterest> interests, Map<Long, ? extends Producer> available) {
        var requested = new HashSet<Long>();
        interests.forEach(interest -> requested.addAll(interest.getPossibleSourceProducers(available)));
        var unrequested = new TreeSet<Long>(available.keySet());
        unrequested.removeAll(requested);
        return Collections.unmodifiableSet(unrequested);
    }

    /**
     * What {@code open()} does.
     *
     * @param sessions each session with its keys, in the order they were built
     * @param disabledProducers the producers to disable, since no session asks for them
     */
    public record Plan(List<Planned> sessions, Set<Long> disabledProducers) {

        /**
         * Whether the feed is a replay feed, its replay session alone: such a feed runs no alive
         * consumer of the SDK's own and no recovery, as in 0.0.x.
         */
        public boolean replay() {
            return sessions.size() == 1 && sessions.getFirst().spec().replay();
        }
    }

    /**
     * One session of the plan.
     *
     * @param routingKeys what its queue is bound with
     * @param takesSnapshotComplete whether its keys include the snapshot completions
     */
    public record Planned(SessionSpec spec, List<String> routingKeys, boolean takesSnapshotComplete) {

        /** The session as the recovery actor knows it; not asked for on a replay feed, which runs no recovery. */
        public SessionInfo info() {
            return new SessionInfo(spec.id(), spec.interest(), takesSnapshotComplete);
        }
    }
}
