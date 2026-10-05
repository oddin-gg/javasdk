package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The numbers the recovery actor works with.
 *
 * @param maxInactivity how long a producer may go without an alive, and a session may lag, before
 *     the producer is down: 0.0.x's max inactivity
 * @param maxRecoveryTime how long a recovery may take before it counts as failed: 0.0.x's max
 *     recovery execution time
 * @param snapshotCompleteTimeout how long a producer's recovery waits for its snapshot complete
 *     after it was asked for, or after a session that awaits it last took something that says it
 *     is on its way, before it counts as failed: well under the maximum recovery time, so a lost
 *     snapshot complete keeps the producer down for minutes, not hours
 * @param initialSnapshotInterval how far back a cold start with no recovery point asks for, or null
 *     for a full snapshot
 * @param nodeId the node id the requests carry, for the recovery info the client reads
 * @param reissues how many times in a row a failed recovery is asked for again
 * @param firstReissueBackoff the pause before the first of them; it doubles for each next one
 * @param cooldown how long a spent cap, the recovery's or the safety net's, stays spent
 * @param aliveInterval how often a producer is expected to send an alive until its alives say
 * @param staleLimit how old live messages may be before the safety net counts them stale
 * @param staleWindow how long they must stay stale before the safety net acts
 * @param resets how many resets the safety net makes per session per cool-down
 * @param firstResetBackoff the least time between two resets of a session; it doubles for each next
 *     one within the cool-down
 * @param eventRecoveries the most event recoveries in flight per producer
 * @param tick how often the actor looks at the time
 */
public record RecoverySettings(
        Duration maxInactivity,
        Duration maxRecoveryTime,
        Duration snapshotCompleteTimeout,
        @Nullable Duration initialSnapshotInterval,
        @Nullable Integer nodeId,
        int reissues,
        Duration firstReissueBackoff,
        Duration cooldown,
        Duration aliveInterval,
        Duration staleLimit,
        Duration staleWindow,
        int resets,
        Duration firstResetBackoff,
        int eventRecoveries,
        Duration tick) {

    /**
     * Five minutes, as the Go SDK has it: recoveries observed there completed in 83 to 139 s, and a
     * snapshot complete rides the queue behind its snapshot. A session still taking the snapshot, or
     * what was queued before the request, puts it off, so a slow one is not given up.
     */
    static final Duration SNAPSHOT_COMPLETE_TIMEOUT = Duration.ofMinutes(5);

    static final int REISSUES = 3;
    static final Duration FIRST_REISSUE_BACKOFF = Duration.ofSeconds(5);
    static final Duration COOLDOWN = Duration.ofMinutes(10);
    static final Duration ALIVE_INTERVAL = Duration.ofSeconds(10);
    static final Duration STALE_LIMIT = Duration.ofMinutes(2);
    static final Duration STALE_WINDOW = Duration.ofMinutes(1);
    static final int RESETS = 3;
    static final Duration FIRST_RESET_BACKOFF = Duration.ofMinutes(1);
    static final int EVENT_RECOVERIES = 128;
    static final Duration TICK = Duration.ofSeconds(1);

    /** What the configuration sets, and the design's numbers for the rest. */
    public static RecoverySettings from(OddsFeedConfiguration configuration) {
        return new RecoverySettings(
                Duration.ofSeconds(configuration.getMaxInactivitySeconds()),
                Duration.ofMinutes(configuration.getMaxRecoveryExecutionMinutes()),
                SNAPSHOT_COMPLETE_TIMEOUT,
                configuration.getInitialSnapshotRecoveryInterval(),
                configuration.getSdkNodeId(),
                REISSUES,
                FIRST_REISSUE_BACKOFF,
                COOLDOWN,
                ALIVE_INTERVAL,
                STALE_LIMIT,
                STALE_WINDOW,
                RESETS,
                FIRST_RESET_BACKOFF,
                EVENT_RECOVERIES,
                TICK);
    }
}
