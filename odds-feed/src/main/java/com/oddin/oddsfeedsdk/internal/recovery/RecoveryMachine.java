package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.internal.clock.ClockOffset;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.oddin.oddsfeedsdk.internal.clock.Stamp;
import com.oddin.oddsfeedsdk.internal.log.Throttle;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.producer.Recovery;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every producer's recovery state, and the rules that change it: the state machine the recovery
 * actor runs. Each method is one fact, handled to the end before the next; what the machine decides
 * goes out through the {@link Outbox} and the {@link RecoveryEvents}, and the outcome of a request
 * comes back as another fact. Nothing here blocks or calls the client.
 *
 * <p><b>Gaps.</b> A gap is what a session may be missing of a producer's messages, and where it
 * starts. A session's queue loses what it held when the connection or its channel goes, when the
 * safety net resets it, and has nothing from before it opened: those gaps start at the session's
 * checkpoint, the running maximum of the timestamps of the live messages and subscribed alives it
 * has finished, or the time of the request whose snapshot complete it has seen. A producer that
 * stops sending - an alive that says the feed is unsubscribed, or no alive for too long - leaves a
 * gap in every session from its last subscribed alive, which says everything before it was sent;
 * the queues still hold whatever they had. A recovery starts from the oldest open gap. Snapshot messages never move a checkpoint, and a gap keeps its start until a
 * recovery that covers it completes, so asking again starts from the same point as the first time.
 * The oldest of the open gaps' starts and the sessions' checkpoints is the producer's resume point:
 * where a client that restarts resumes to miss nothing, published through {@link
 * #publishResumePoints}.
 *
 * <p><b>Recoveries.</b> A producer has at most one recovery in flight; whatever happens meanwhile
 * joins it. A recovery covers the gaps open when it was asked for. It completes once every session
 * that receives the producer and takes snapshot completions has seen its snapshot complete; a gap
 * opened later asks for one more. A loss that takes the recovery's own snapshot with it - the
 * connection, or the channel of a session that has not seen the snapshot complete yet - gives it up,
 * and a new one is asked for. So does a gap of the producer's, which may have lost the request: an
 * alive still unsubscribed after the API accepted it, or no alive for too long; the next alive asks
 * again. A recovery is asked for only while an alive says the producer is there, the transport has
 * reported the connection up, and every session that receives the producer has a bound queue. One
 * that fails, or times out - no snapshot complete within {@link
 * RecoverySettings#snapshotCompleteTimeout()} once nothing more of it comes, or within the maximum
 * recovery time at all - is asked for again with backoff, at most {@link
 * RecoverySettings#reissues()} times in a row; then the producer stays down for a cool-down, or until
 * an alive arrives after a gap.
 *
 * <p><b>The safety net.</b> A message's age is the time it was taken against its timestamp, with the
 * producer's clock offset measured on its own alives. When a session's live messages of a producer
 * stay older than the limit for the window, the net asks for a recovery of every enabled producer
 * of the session, and only once the API has accepted them all has the session's channel replaced.
 * It starts only when every one of those producers can be asked for now: none has a recovery in
 * flight, is in backoff or in its cool-down, is silent, has a session without a bound queue, or has a
 * gap open, the producer's or a session's, whose own recovery comes first.
 * Otherwise it waits, with no request, no lagging and no backoff, and a later stale sample tries
 * again: the reset drops the backlog of every producer the session receives, and a producer that
 * could not be asked for right after it would miss what the reset dropped. A recovery of the net
 * that ends before the reset is made - refused, timed out, given up, completed - cancels the
 * reset. Once the transport reports it done, the session has lost its queue like a lost channel,
 * and every recovery still in flight for a producer of the session, which was asked for before the
 * reset and part of whose messages may have gone with the old queue, is given up and asked for
 * again in that step, whether the session waited for it or not. The net is
 * off for a producer whose last alive is older than two alive intervals, and paused for a producer
 * with a recovery in flight or a gap open. It resets each session at most {@link
 * RecoverySettings#resets()} times per cool-down, with backoff between resets, counted over the
 * resets of the cool-down only; when spent the
 * session is lagging. Lagging takes no producer down, but a producer the session processes later
 * than the maximum inactivity stays down for that delay until the session is on time again.
 * A window of stale samples that began before a recovery of the producer ended, or before an alive
 * made a stale clock offset fresh again, does not count that time: it starts again with the first
 * stale sample after it, and a sample taken before is not the net's.
 *
 * <p>The reset drops what the session's queue holds, and a recovery repeats it only from the
 * producer's stateful recovery window on. A session whose checkpoint of an enabled producer it
 * receives, one the net could ask for now or not, lies before where that request would start - it
 * has lagged longer than the window - is not reset: it is lagging instead, with nothing asked,
 * and stops lagging when it is on time again. The start is moved on by a margin of ten minutes, at
 * most half the window, for the time from the acceptance to the recovery asked into the new queue.
 * The same is checked when the last recovery is accepted, and a reset the window has overtaken is
 * cancelled like a refused request. A reset is not made once the feed is {@link #closing}, nor
 * for a producer that was disabled while its recovery was asked for, nor when a producer was
 * enabled since or one's recovery is no longer the one the net asked for. Another session that
 * receives a producer of the session and has lost its channel or has a reset under way holds the
 * net back at the start, since that producer cannot be asked for until the queue is back; that is
 * checked again at the last acceptance, where it cancels the reset with no backoff, as does a lost
 * connection or channel. An enabled producer that is silent, in backoff, capped or mid-recovery thus
 * holds the net back for all the sessions that receive it.
 *
 * <p><b>Time.</b> Every duration the machine measures between two local events - a producer's
 * inactivity, the deadlines of a recovery and of an event recovery, the backoffs, the cool-down, the
 * safety net's windows, how long an event recovery's status is kept - runs on the feed clock's monotonic side, so a step of the wall clock
 * neither fires a deadline nor holds one back. What the machine publishes - the last message's
 * timestamp, the resume point, a recovery's start, the instants of status changes - and what it
 * compares with a producer's own timestamps - the clock offset, a message's age, the cap at the
 * producer's now - is wall time. The monotonic side is at least 1, as 0 stands for "never" here.
 * A step of the wall clock after an offset was measured would read as age, so the offset is moved
 * by the step where it is read: see {@link #offsetAt}.
 *
 * <p>Not safe for concurrent use: the recovery actor's thread is the only one that calls it.
 */
final class RecoveryMachine {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryMachine.class);

    private static final String CLAMPED = "Recovery {} of producer {}: the gap began at {}, before the producer's"
            + " stateful recovery window of {} minutes; asking from {}, so {} of what the producer sent cannot be recovered";

    /**
     * How much of the stateful recovery window's start the safety net keeps clear of before a reset:
     * see {@link #beyondWindow}.
     */
    private static final Duration WINDOW_MARGIN = Duration.ofMinutes(10);

    /** A sample's age when the producer's clock offset is not known. */
    private static final long UNKNOWN_AGE = Long.MIN_VALUE;

    /** No resume point: no session receives the producer, or none was published yet. */
    private static final long NO_POINT = -1;

    /**
     * How many of a producer's recoveries, and as many of its event recoveries, that ended without
     * completing are remembered: each sends a snapshot of its own at most, so the traffic that puts
     * off a deadline stays bounded.
     */
    private static final int ENDED_REMEMBERED = 8;

    private final Producers producers;
    private final RecoverySettings settings;
    private final Outbox outbox;
    private final RecoveryEvents events;
    private final FeedClock clock;
    private final RecoveryCounters counters;
    /** Every event recovery's status, which any thread reads; written here only. */
    private final EventRecoveryStatuses statuses;

    private final RequestIds ids;

    /** Every producer of the producer list, in its order. */
    private final Map<Long, Track> tracks = new LinkedHashMap<>();

    private final Map<Integer, SessionState> sessions = new LinkedHashMap<>();
    /** In the order they were sent, so what ends in one step ends in that order. */
    private final Map<Long, EventRecovery> eventRecoveries = new LinkedHashMap<>();
    /** The callers waiting for whether the API took an event recovery, by request id, in the order sent. */
    private final Map<Long, CompletableFuture<@Nullable Long>> replies = new LinkedHashMap<>();
    /** Event recoveries asked for while a session that would receive them was being reset. */
    private final List<DeferredEvent> deferred = new ArrayList<>();
    /** The API's refusals of event recoveries, which the caller hears of only as null. */
    private final Throttle refusedByApi = new Throttle();
    /** The deferred event recoveries that expired while a reset they waited for never ended. */
    private final Throttle expiredDeferred = new Throttle();

    private boolean started;
    /**
     * Whether the feed is closing: from then on a resume point is published only when it goes back,
     * so sessions closing one by one at shutdown cannot carry it past what one of them had left.
     */
    private boolean closing;

    private long startedAt;
    /**
     * Whether the transport has reported its connection up, with every session's channel bound: not
     * before its first up, so the first recovery cannot go out to queues that do not exist yet.
     */
    private boolean connected;
    /** The number of the last gap opened; a recovery covers the gaps up to the number at its start. */
    private long gapSeq;
    /**
     * The number of the last request sent, a recovery's or an event recovery's: each takes the next
     * as it goes out, so what was asked for before what is told by the numbers, never by the clock,
     * which two requests can read the same.
     */
    private long sentSeq;
    /** The number of the last reset the safety net set out to make. */
    private long resetSeq;

    RecoveryMachine(
            Producers producers,
            RecoverySettings settings,
            Outbox outbox,
            RecoveryEvents events,
            FeedClock clock,
            RecoveryCounters counters,
            EventRecoveryStatuses statuses,
            RandomGenerator random) {
        this.producers = producers;
        this.settings = settings;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
        this.counters = counters;
        this.statuses = statuses;
        this.ids = new RequestIds(random);
        for (Producer producer : producers.getAvailableProducers().values()) {
            tracks.put(
                    producer.getId(),
                    new Track(producer, settings.aliveInterval().toMillis()));
        }
    }

    // ---- the lifecycle

    /**
     * A session, before or after {@link #start}. One that opens later has nothing from before it
     * opened: it starts from the producer's recovery point now, and asks for a recovery.
     */
    void sessionOpened(SessionInfo info) {
        if (sessions.containsKey(info.id())) {
            LOG.warn("Session {} opened twice; the second is ignored", info.id());
            return;
        }
        var session = new SessionState(info);
        long mono = mono();
        // a client's alive-only session receives no producer's messages: no lane, so its channel's
        // loss and its pace take no producer down, and no recovery waits for its snapshot complete
        boolean receives = info.interest() != MessageInterest.SYSTEM_ALIVE_ONLY;
        for (Track track : tracks.values()) {
            if (receives && info.interest().isProducerInScope(track.producer)) {
                var lane = new Lane();
                if (started) {
                    Gap gap = openGap(null, recoveryPoint(track), false);
                    lane.gap = gap;
                    lane.checkpoint = gap.from;
                }
                session.lanes.put(track.id, lane);
            }
        }
        sessions.put(info.id(), session);
        if (started) {
            for (Long producerId : session.lanes.keySet()) {
                Track track = track(producerId);
                markDown(track, StatusCause.SESSION_OPENED);
                maybeRequest(track, mono);
            }
        }
    }

    /**
     * A session closed: it leaves the checkpoints and the completions at once, so a recovery that
     * waited only for it completes. Once the feed is {@link #closing}, which is when the sessions
     * close, it leaves the checkpoints only: a recovery or an event recovery it was waiting for has
     * not completed - an event recovery fails as the machine closes - and no producer is up for it.
     */
    void sessionClosed(int id) {
        SessionState session = sessions.remove(id);
        if (session == null || closing) {
            return;
        }
        long mono = mono();
        for (Long producerId : session.lanes.keySet()) {
            Track track = track(producerId);
            Active active = track.active;
            if (active != null && active.awaited.remove(id)) {
                boolean seenAny = !active.seen.isEmpty();
                active.seen.remove(id);
                // a snapshot complete stands for the API's acceptance; with none seen, and none left
                // to wait for, the API's answer decides
                if (active.seen.containsAll(active.awaited) && (active.accepted || seenAny)) {
                    complete(track, active, mono, true);
                }
            } else if (active == null) {
                upIfNothingMissing(track);
            }
        }
        releaseDeferred();
        for (EventRecovery recovery : new ArrayList<>(eventRecoveries.values())) {
            if (recovery.awaited.remove(id)) {
                boolean seenAny = !recovery.seen.isEmpty();
                recovery.seen.remove(id);
                boolean answered = !replies.containsKey(recovery.requestId);
                if (recovery.seen.containsAll(recovery.awaited) && (answered || seenAny)) {
                    eventCompleted(recovery);
                }
            }
        }
    }

    /**
     * The feed is open: every session misses everything before now, from the recovery point the
     * client set, or from nothing - a full snapshot. Each producer asks at its first alive, as in
     * 0.0.x, once the transport has reported its first {@link #connectionUp}.
     *
     * <p>A gap a session's lane got before - the connection down or its channel lost, told before
     * the start - is replaced, not joined: nothing was received before the start, so there is
     * nothing more to miss than the seed says, and joining it at the checkpoint 0 it started from
     * would ask for a full snapshot.
     */
    void start() {
        if (started) {
            return;
        }
        started = true;
        startedAt = mono();
        for (SessionState session : sessions.values()) {
            for (Map.Entry<Long, Lane> entry : session.lanes.entrySet()) {
                Lane lane = entry.getValue();
                Gap gap = openGap(null, seed(track(entry.getKey())), false);
                lane.gap = gap;
                // the start it got - an initial interval counted back from now, say - is where a
                // later loss starts too, while the session has processed nothing to move it
                lane.checkpoint = gap.from;
            }
        }
    }

    /**
     * The feed has begun to close: from now on the published resume points only go back. A session
     * that closes takes its checkpoint out of the point; at shutdown that would move the point past
     * its unprocessed messages, and the point the client persists would depend on the order the
     * sessions close in. A gap opened meanwhile still takes the point back.
     */
    void closing() {
        closing = true;
    }

    /**
     * The actor closed with facts still queued, not handled: each published resume point goes back
     * to its producer's last subscribed alive, or to 0, a full snapshot, without one. Of those facts
     * only an unsubscribed alive could have taken a point further back, with the gap it opens from
     * that alive - or, with none, from a time only the fact has. A session opened among them starts
     * from the point there is or that alive, a lost queue from a checkpoint; the rest move nothing
     * back. So the point is never ahead of what a fact left could have needed; and it only goes
     * back, as every point does once the feed is closing.
     */
    void unhandledAtClose() {
        for (Track track : tracks.values()) {
            // NO_POINT is below any point, so a producer with nothing published - the feed never
            // started, or no session received it, and nothing was processed - keeps the client's start
            long floor = Math.min(track.published, track.safePoint);
            if (floor != track.published) {
                LOG.warn(
                        "The recovery actor closed with facts it could not handle: producer {} resumes from {}",
                        track.id,
                        floor == 0 ? "a full snapshot" : Instant.ofEpochMilli(floor));
                track.published = floor;
                producers.setResumePoint(track.id, floor);
            }
        }
    }

    /**
     * The feed is closing: whoever waits for an event recovery hears it was not accepted, and the
     * event recoveries still in flight have failed.
     */
    void close() {
        for (EventRecovery recovery : eventRecoveries.values()) {
            ended(recovery, EventRecoveryStatus.State.FAILED, EventRecoveryStatuses.FEED_CLOSED);
        }
        eventRecoveries.clear();
        for (CompletableFuture<@Nullable Long> reply : replies.values()) {
            outbox.reply(reply, null);
        }
        replies.clear();
        for (DeferredEvent event : deferred) {
            outbox.reply(event.reply(), null);
        }
        deferred.clear();
    }

    // ---- the transport

    /**
     * The connection is gone, and every queue with what it held: each session misses everything
     * after its checkpoint, and the recoveries in flight lose their snapshots.
     */
    void connectionDown() {
        connected = false;
        long mono = mono();
        for (Track track : tracks.values()) {
            if (track.active != null) {
                giveUp(track, mono, "its snapshot went with the connection", true);
            }
        }
        for (SessionState session : sessions.values()) {
            session.pending = null;
            for (Lane lane : session.lanes.values()) {
                lane.gap = openGap(lane.gap, lane.checkpoint, false);
                lane.above = false;
            }
        }
        giveUpEvents(null);
        for (Track track : tracks.values()) {
            if (receivers(track) > 0) {
                markDown(track, StatusCause.CONNECTION_LOST);
            }
        }
    }

    /**
     * The connection is up with every channel open, the first time or back after a loss: recoveries
     * go out now to producers whose last alive is recent, else at their next alives.
     */
    void connectionUp() {
        connected = true;
        long mono = mono();
        for (Track track : tracks.values()) {
            maybeRequest(track, mono);
            upIfNothingMissing(track);
        }
    }

    /**
     * A session's channel was lost, outside the safety net: its queue lost what it held. A recovery
     * that waited for this session's snapshot complete loses it too. Nothing is asked for the
     * session's producers until {@link #channelReopened} says a new queue is bound: what the feed
     * sends before then reaches no queue, snapshot complete included.
     */
    void channelLost(int id) {
        SessionState session = sessions.get(id);
        if (session == null) {
            return;
        }
        session.pending = null;
        session.lost = true;
        long mono = mono();
        // another session's reset that asked for a producer this one receives: its recovery cannot be asked for
        // again until this queue is back, and a reset now would drop the backlog meanwhile. A loss, so no backoff
        for (SessionState other : sessions.values()) {
            if (other != session && other.pending != null && sharesEnabledProducer(session, other)) {
                cancelReset(other, mono, false);
            }
        }
        queueLost(session, StatusCause.CHANNEL_LOST, mono, 0);
    }

    /** Whether both sessions receive a producer that is enabled. */
    private boolean sharesEnabledProducer(SessionState one, SessionState other) {
        for (Long producerId : one.lanes.keySet()) {
            if (other.lanes.containsKey(producerId) && enabled(track(producerId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A new channel replaced the one lost, by the transport's reopen, a reconnect or a reset: its
     * queue is bound, and what the session misses is asked for now, or at the next alives.
     */
    void channelReopened(int id) {
        SessionState session = sessions.get(id);
        if (session == null || !session.lost) {
            return;
        }
        session.lost = false;
        long mono = mono();
        for (Long producerId : session.lanes.keySet()) {
            maybeRequest(track(producerId), mono);
        }
        releaseDeferred();
    }

    /**
     * The safety net's reset is made: the session's queue lost what it held, as with a lost
     * channel. Every recovery still in flight for a producer of the session was asked for before
     * the reset, so part of what it sent went with the old queue, whether the session waited for its
     * snapshot complete or not: each is given up, and asked for again in this step.
     *
     * <p>A reset with the transport stays one through a lost connection or channel: replacing the
     * channel late can still drop what the new one holds, so its report counts all the same.
     *
     * @param number the reset's own number, so a report of no reset underway changes nothing
     * @param replaced whether the channel was replaced; it was not when the transport could not take
     *     the reset at all, and then nothing was dropped
     */
    void resetDone(int id, long number, boolean replaced) {
        SessionState session = sessions.get(id);
        if (session == null || session.underway == 0 || session.underway != number) {
            return;
        }
        session.underway = 0;
        long mono = mono();
        if (replaced) {
            // only a reset made counts against the cap and the backoff
            resetsWithinCooldown(session, mono);
            session.resets.addLast(mono);
            session.nextResetAt = mono + backoff(settings.firstResetBackoff(), session.resets.size());
            counters.resets.incrementAndGet();
            events.safetyNetReset(id, session.underwayProducer, session.underwayAge);
            if (session.lagging) {
                session.lagging = false;
                events.lagging(id, false);
            }
            queueLost(session, StatusCause.SAFETY_NET_RESET, mono, session.underwaySeq);
        } else {
            // nothing was dropped, so the recoveries in flight stay, but the snapshot completes ignored
            // meanwhile are gone; the net waits as long as after the last reset made, and the backoff does
            // not grow for one not made
            LOG.warn("The safety net's reset of session {} was not made", id);
            session.nextResetAt =
                    mono + backoff(settings.firstResetBackoff(), Math.max(1, resetsWithinCooldown(session, mono)));
            notSeen(session, mono, 0);
        }
        releaseDeferred();
    }

    /**
     * The event recoveries that waited for a session's channel are asked for once none waits. The
     * maximum recovery time counts from when the client asked, the wait included: one past it expires
     * as at a tick, and is not sent.
     */
    private void releaseDeferred() {
        for (DeferredEvent event : new ArrayList<>(deferred)) {
            Track track = track(event.producerId());
            if (!awaitsChannel(track)) {
                deferred.remove(event);
                if (pastMaximum(event.deferredAt(), mono())) {
                    expired(event);
                } else {
                    recoverEvent(
                            event.producerId(), event.eventId(), event.stateful(), event.reply(), event.deferredAt());
                }
            }
        }
    }

    /**
     * The session's queue lost what it held: it misses everything after its checkpoints, and a
     * recovery or event recovery that waited for its snapshot complete will not see it.
     */
    private void queueLost(SessionState session, StatusCause cause, long mono, long askedUpTo) {
        for (Map.Entry<Long, Lane> entry : session.lanes.entrySet()) {
            Lane lane = entry.getValue();
            lane.gap = openGap(lane.gap, lane.checkpoint, false);
            lane.above = false;
        }
        for (Long producerId : session.lanes.keySet()) {
            markDown(track(producerId), cause);
        }
        notSeen(session, mono, askedUpTo);
    }

    /**
     * The session will not see the snapshot completes it has not seen yet: the recoveries and event
     * recoveries waiting for them are given up, and asked for again as far as anything is missing.
     *
     * @param askedUpTo the recoveries asked for up to this request in the order sent are given up as
     *     well, those the session did not wait for included: what they sent went with its queue, and
     *     one that stays in flight holds back the recovery that repeats it. 0 for none.
     */
    private void notSeen(SessionState session, long mono, long askedUpTo) {
        int id = session.info.id();
        giveUpEvents(id);
        for (Long producerId : session.lanes.keySet()) {
            Track track = track(producerId);
            Active active = track.active;
            if (active != null) {
                if (active.awaited.contains(id) && !active.seen.contains(id)) {
                    giveUp(track, mono, "its snapshot went with session " + id + "'s queue", true);
                } else if (active.seq <= askedUpTo) {
                    giveUp(track, mono, "what it sent went with session " + id + "'s queue, in the reset", true);
                }
            }
            maybeRequest(track, mono);
        }
    }

    // ---- the feed

    /**
     * An alive from the SDK's own alive channel: the producer is there. It measures the producer's
     * clock offset and alive interval; subscribed, it is the point everything before was sent;
     * unsubscribed, the producer has stopped sending since the last one that was. One without a
     * positive timestamp is ignored: as the producer's clock it would put the producer's now at or
     * before the epoch, and every recovery and resume point capped at it with it.
     *
     * @param markAt the stamp the safe point is taken from: {@code generatedAt}, or the producer's
     *     now at receipt when the alive is stamped ahead of it. The offset is measured from {@code
     *     generatedAt} as it is: it is the producer's clock
     * @param received when the SDK received it: the wall reading is compared with the producer's
     *     clock and published, the monotonic one measures the alive interval and orders it against
     *     the recovery's acceptance
     */
    void alive(long producerId, long generatedAt, long markAt, Stamp received, boolean subscribed) {
        Track track = known(producerId);
        if (track == null || !enabled(track)) {
            return;
        }
        if (generatedAt <= 0) {
            // the alive dispatcher refuses and logs it before it gets here
            return;
        }
        long mono = mono();
        // the alive after the one that gave a recovery up: it may be asked for again
        track.awaitsAlive = false;
        if (track.lastAliveAt == 0 || received.mono() - track.lastAliveAt > 2 * track.aliveInterval) {
            // the offset was stale, or there was none: a window of stale samples from before does not count it
            track.offsetFreshAt = received.mono();
        }
        if (track.lastAliveAt != 0) {
            // a second at least, and the maximum inactivity at most, unless that is less
            long measured = Math.clamp(
                    received.mono() - track.lastAliveAt,
                    1_000,
                    Math.max(1_000, settings.maxInactivity().toMillis()));
            track.aliveInterval += (measured - track.aliveInterval) / 4;
        }
        track.lastAliveAt = received.mono();
        track.lastAliveMark = markAt;
        track.offset = received.wall() - generatedAt;
        track.skew = received.wall() - received.mono();
        producers.setLastMessageTimestamp(producerId, Math.max(1, received.wall()));
        if (track.inactive) {
            // an alive after a gap re-arms the cap at once
            track.inactive = false;
            rearm(track);
        }
        if (subscribed) {
            track.safePoint = Math.max(track.safePoint, markAt);
        } else {
            openProducerGap(track, markAt);
            Active active = track.active;
            if (active != null && active.accepted && received.mono() >= active.acceptedAt) {
                // still unsubscribed after the producer took the request: it lost it, restarting say,
                // and will send neither the snapshot nor its snapshot complete
                giveUp(track, mono, "the producer says unsubscribed since it accepted it", false);
                track.awaitsAlive = true;
            }
            markDown(track, StatusCause.UNSUBSCRIBED);
        }
        maybeRequest(track, mono);
    }

    /** An alive whose stamp is not ahead of the producer's now: its own stamp is its mark. */
    void alive(long producerId, long generatedAt, Stamp received, boolean subscribed) {
        alive(producerId, generatedAt, generatedAt, received, subscribed);
    }

    /**
     * A session has finished a message of the producer. A live one moves the session's checkpoint
     * and is the safety net's sample; a snapshot one, from a recovery, does neither. One without a
     * positive timestamp moves none of that, as an alive stamped so, which {@link #sessionAlive}
     * ignores, moves none: it was delivered, and a snapshot one still puts off the deadline for its
     * snapshot complete.
     *
     * @param markAt the stamp the session's checkpoint is taken from: {@code generatedAt}, or the
     *     producer's now at receipt when the message is stamped ahead of it. What is published and
     *     compared with the request is the message's own stamp
     * @param taken when the session's dispatcher took it: the wall reading is published and
     *     compared with the producer's clock, the monotonic one orders it against the recovery that
     *     ended and times the safety net's window
     * @param requestId the recovery's request id a snapshot message carries, 0 for a live one
     */
    void processed(int id, long producerId, long generatedAt, long markAt, Stamp taken, long requestId) {
        Track track = known(producerId);
        SessionState session = sessions.get(id);
        Lane lane = session == null || track == null ? null : session.lanes.get(producerId);
        if (session == null || track == null || lane == null) {
            return;
        }
        producers.setLastMessageTimestamp(producerId, Math.max(1, taken.wall()));
        lane.lastAt = taken.mono();
        if (generatedAt <= 0) {
            // not a time: the stamp would publish 0 or less as the last processed timestamp, and as
            // the sample it would read as an age from the epoch, which starts a recovery. A snapshot
            // message is still heard: the request id says it is on its way, whatever it is stamped
            if (requestId != 0) {
                heard(track, id, generatedAt, requestId);
            }
            return;
        }
        producers.setLastProcessedMessageGenTimestamp(producerId, generatedAt);
        heard(track, id, generatedAt, requestId);
        if (requestId == 0) {
            lane.checkpoint = Math.max(lane.checkpoint, markAt);
            sample(session, lane, track, generatedAt, taken);
        }
    }

    /** A message whose stamp is not ahead of the producer's now: its own stamp is its mark. */
    void processed(int id, long producerId, long generatedAt, Stamp taken, long requestId) {
        processed(id, producerId, generatedAt, generatedAt, taken, requestId);
    }

    /**
     * A session has finished an alive from its own queue: everything the producer sent before it is
     * done, so a subscribed one moves the session's checkpoint. Both kinds are samples. One without
     * a positive timestamp is ignored, as {@link #alive} ignores it. The checkpoint is taken from
     * {@code markAt}, as {@link #processed} takes it.
     */
    void sessionAlive(int id, long producerId, long generatedAt, long markAt, Stamp taken, boolean subscribed) {
        if (generatedAt <= 0) {
            // not a time: the alive dispatcher logs the same alive on the SDK's own channel
            return;
        }
        Track track = known(producerId);
        SessionState session = sessions.get(id);
        Lane lane = session == null || track == null ? null : session.lanes.get(producerId);
        if (session == null || track == null || lane == null) {
            return;
        }
        producers.setLastProcessedMessageGenTimestamp(producerId, generatedAt);
        lane.lastAt = taken.mono();
        heard(track, id, generatedAt, 0);
        if (subscribed) {
            lane.checkpoint = Math.max(lane.checkpoint, markAt);
        }
        sample(session, lane, track, generatedAt, taken);
    }

    /** An alive whose stamp is not ahead of the producer's now: its own stamp is its mark. */
    void sessionAlive(int id, long producerId, long generatedAt, Stamp taken, boolean subscribed) {
        sessionAlive(id, producerId, generatedAt, generatedAt, taken, subscribed);
    }

    /**
     * Whether what a session took says the recovery in flight is still on its way to it: a message
     * of its snapshot, or one the snapshot and its snapshot complete queue behind - a live message or
     * an alive sent before the request, or a snapshot message of an earlier recovery of the producer
     * that failed or was given up, or of an event recovery asked for before it, in flight or ended
     * without completing. Any of them puts off the deadline for its snapshot complete. An event
     * recovery asked for since does not: one after another, they would hold a recovery whose
     * snapshot complete is lost for the maximum recovery time. An event recovery's deadline is put
     * off the same way, by its own snapshot and by what queues ahead of it: a recovery or an event
     * recovery asked for before it, in flight or ended without completing. Before is the order the
     * requests were sent in, {@link #sentSeq}: two sent at one clock reading are still one before the
     * other.
     */
    private void heard(Track track, int id, long generatedAt, long requestId) {
        for (EventRecovery event : eventRecoveries.values()) {
            if (event.producerId == track.id
                    && event.awaited.contains(id)
                    && !event.seen.contains(id)
                    && ahead(track, event, generatedAt, requestId)) {
                event.heardAt = mono();
            }
        }
        Active active = track.active;
        if (active != null
                && active.awaited.contains(id)
                && !active.seen.contains(id)
                && (requestId == 0 ? generatedAt < active.requestedAt : ahead(track, active, requestId))) {
            active.heardAt = mono();
        }
    }

    /**
     * Whether what a session took is the event recovery's own snapshot, or queued ahead of it: a
     * live message or an alive generated before it was asked for, or a snapshot message of a
     * recovery or an event recovery of the producer asked for before it, in flight still or ended
     * without completing.
     */
    private boolean ahead(Track track, EventRecovery event, long generatedAt, long requestId) {
        if (requestId == 0) {
            return generatedAt < event.requestedAt;
        }
        if (requestId == event.requestId) {
            return true;
        }
        Active active = track.active;
        if (active != null && active.requestId == requestId) {
            return active.seq < event.seq;
        }
        EventRecovery other = eventRecoveries.get(requestId);
        if (other != null) {
            return other.producerId == track.id && other.seq < event.seq;
        }
        return askedBefore(track.ended, requestId, event.seq) || askedBefore(track.endedEvents, requestId, event.seq);
    }

    /** Whether the request id is one of {@code ended}, sent before the request numbered {@code seq}. */
    private static boolean askedBefore(ArrayDeque<Ended> ended, long requestId, long seq) {
        for (Ended one : ended) {
            if (one.requestId() == requestId) {
                return one.seq() < seq;
            }
        }
        return false;
    }

    /** Whether a snapshot message of {@code requestId} is the recovery's own, or queued ahead of it. */
    private boolean ahead(Track track, Active active, long requestId) {
        if (requestId == active.requestId) {
            return true;
        }
        EventRecovery event = eventRecoveries.get(requestId);
        if (event != null) {
            return event.producerId == track.id && event.seq < active.seq;
        }
        // what ended before the one in flight was asked for: the one in flight is the only one at a time
        return track.ended.stream().anyMatch(one -> one.requestId() == requestId)
                || askedBefore(track.endedEvents, requestId, active.seq);
    }

    /** A session has seen a snapshot complete; an id the actor has not in flight is counted, and ignored. */
    void snapshotComplete(int id, long producerId, long requestId) {
        Track track = known(producerId);
        SessionState session = sessions.get(id);
        if (track == null || (session != null && session.underway != 0)) {
            // from the queue the reset replaces: what it completes is asked for again once it is done
            return;
        }
        Active active = track.active;
        if (active != null && active.requestId == requestId) {
            if (active.awaited.contains(id)) {
                active.seen.add(id);
                PendingReset pending = session == null ? null : session.pending;
                if (session != null && pending != null && pending.asked.containsKey(producerId)) {
                    // the session has what the recovery sent: a reset now would drop it
                    cancelReset(session, mono(), true);
                }
                if (!active.accepted) {
                    // a snapshot complete says the API took the request, whatever it answers later
                    accepted(track, active, mono());
                }
                if (track.active == active && active.seen.containsAll(active.awaited)) {
                    complete(track, active, mono(), true);
                }
            }
            return;
        }
        EventRecovery event = eventRecoveries.get(requestId);
        if (event != null && event.producerId == producerId) {
            if (event.awaited.contains(id)) {
                // a session's second snapshot complete is no sign of life for the sessions still awaited
                boolean first = event.seen.add(id);
                if (event.seen.containsAll(event.awaited)) {
                    // its status ends before a caller still waiting hears the API took it
                    eventCompleted(event);
                } else {
                    if (first) {
                        event.heardAt = mono();
                    }
                    CompletableFuture<@Nullable Long> reply = replies.remove(requestId);
                    if (reply != null) {
                        // a snapshot complete says the API took the request: the caller hears so now
                        outbox.reply(reply, requestId);
                    }
                }
            }
            return;
        }
        counters.unknownCompletions.incrementAndGet();
        LOG.debug("Snapshot complete of producer {} for request {}, which is not in flight", producerId, requestId);
    }

    // ---- the REST workers and the client

    /** A request's outcome: accepted when {@code failure} is null. */
    void answered(long requestId, @Nullable Exception failure) {
        long mono = mono();
        CompletableFuture<@Nullable Long> reply = replies.remove(requestId);
        if (reply != null) {
            EventRecovery event = eventRecoveries.get(requestId);
            if (failure == null) {
                if (event != null) {
                    event.heardAt = mono;
                }
                if (event != null && event.awaited.isEmpty()) {
                    // no session takes snapshot completes: nothing would ever complete it. Its
                    // status ends first: a caller that reads it once the future completes sees it ended
                    eventCompleted(event);
                }
                outbox.reply(reply, requestId);
            } else {
                EventRecovery refused = eventRecoveries.remove(requestId);
                if (refused != null) {
                    endedWithoutCompleting(refused);
                    ended(
                            refused,
                            EventRecoveryStatus.State.FAILED,
                            "the API did not accept it: " + failure.getMessage());
                }
                counters.eventRefused.incrementAndGet();
                long refusals = refusedByApi.count();
                if (Throttle.due(refusals)) {
                    LOG.warn(
                            "Event recovery request {} not accepted: {}, {} so far",
                            requestId,
                            failure.getMessage(),
                            refusals);
                } else {
                    LOG.debug("Event recovery request {} not accepted: {}", requestId, failure.getMessage());
                }
                outbox.reply(reply, null);
            }
            return;
        }
        for (Track track : tracks.values()) {
            Active active = track.active;
            if (active != null && active.requestId == requestId) {
                if (active.accepted) {
                    // a snapshot complete came first and said the API took it: the answer changes nothing
                    LOG.debug("Request {} answered after a snapshot complete of it", requestId);
                } else if (failure == null) {
                    accepted(track, active, mono);
                } else {
                    failed(track, active, mono, "the API did not accept it: " + failure.getMessage());
                }
                return;
            }
        }
        LOG.debug("Request {} answered after it was given up", requestId);
    }

    /**
     * The client asks for one event's messages again. {@code reply} gets the request id once the
     * API has accepted it, or null when it did not or too many are in flight already.
     *
     * @throws IllegalArgumentException through {@code reply}, for a producer the list does not have
     */
    void recoverEvent(long producerId, URN eventId, boolean stateful, CompletableFuture<@Nullable Long> reply) {
        recoverEvent(producerId, eventId, stateful, reply, mono());
    }

    /**
     * {@code askedAt}, on the monotonic clock, is when the client asked: now, or for one that waited
     * for a session's channel, when it began to wait. The maximum recovery time counts from it; the
     * snapshot complete's silence deadline and the request's place in the order count from the
     * request going out.
     */
    private void recoverEvent(
            long producerId, URN eventId, boolean stateful, CompletableFuture<@Nullable Long> reply, long askedAt) {
        if (reply.isDone()) {
            // the caller stopped waiting: a request sent now would carry an id it never got
            counters.eventCallerGone.incrementAndGet();
            LOG.debug("Event recovery of {} not asked for: its caller stopped waiting", eventId);
            return;
        }
        Track track = known(producerId);
        if (track == null) {
            outbox.fail(reply, new IllegalArgumentException("Unknown producer " + producerId));
            return;
        }
        if (!connected) {
            // its snapshot would go to queues that are gone, or not bound yet, and nothing would give
            // it up
            counters.eventRefused.incrementAndGet();
            LOG.debug("Event recovery of {} not accepted: the connection is not up", eventId);
            outbox.reply(reply, null);
            return;
        }
        // a deferred one whose caller stopped waiting will not be asked for, and takes no room
        deferred.removeIf(event -> {
            boolean gone = event.reply().isDone();
            if (gone) {
                counters.eventCallerGone.incrementAndGet();
            }
            return gone;
        });
        long inFlight = eventRecoveries.values().stream()
                        .filter(recovery -> recovery.producerId == producerId)
                        .count()
                + deferred.stream()
                        .filter(event -> event.producerId() == producerId)
                        .count();
        if (inFlight >= settings.eventRecoveries()) {
            counters.eventRefused.incrementAndGet();
            LOG.debug("Event recovery of {} not accepted: {} in flight for producer {}", eventId, inFlight, producerId);
            outbox.reply(reply, null);
            return;
        }
        if (awaitsChannel(track)) {
            // a session that receives it is being reset, or has lost its channel: its snapshot could
            // go to either channel, or to none
            LOG.debug("Event recovery of {} waits for a session's channel", eventId);
            deferred.add(new DeferredEvent(producerId, eventId, stateful, reply, askedAt));
            return;
        }
        long requestId = ids.next(this::inFlight);
        long wall = wall();
        var recovery = new EventRecovery(
                requestId, ++sentSeq, producerId, eventId, wall, askedAt, mono(), producerNow(track), awaited(track));
        eventRecoveries.put(requestId, recovery);
        statuses.pending(requestId, producerId, eventId, Instant.ofEpochMilli(recovery.issuedWall));
        replies.put(requestId, reply);
        counters.eventRequested.incrementAndGet();
        outbox.request(new Outbox.Call.Event(producerId, track.producer.getName(), requestId, eventId, stateful));
    }

    // ---- the time

    /**
     * The clock moved on: recoveries time out, producers without alives go down, sessions that lag
     * take producers down and bring them back, recoveries waiting for their backoff go out, and
     * event recoveries expire.
     */
    void tick() {
        long mono = mono();
        cancelResetsWaitingForDisabledProducers(mono);
        for (Track track : tracks.values()) {
            if (!enabled(track)) {
                forgetStaleWindows(track);
                continue;
            }
            track.disabledNoticed = false;
            if ((!track.down || track.cause == StatusCause.PROCESSING_DELAY) && underway(track)) {
                // enabled while a session that receives it is being reset, which skipped it: it is not up until the
                // recovery after, and a session taking messages on time again must not bring it up before
                markDown(track, StatusCause.SAFETY_NET_RESET);
            }
            Active active = track.active;
            if (active != null) {
                if (mono - active.issuedMono > settings.maxRecoveryTime().toMillis()) {
                    if (underway(track)) {
                        // the reset gives it up once done, and what it lost with the queue is no fault of the request
                        giveUp(
                                track,
                                mono,
                                "no snapshot complete within " + settings.maxRecoveryTime() + ", a reset under way",
                                false);
                    } else {
                        counters.timedOut.incrementAndGet();
                        failed(track, active, mono, "no snapshot complete within " + settings.maxRecoveryTime());
                    }
                } else if (mono - active.heardAt
                                > settings.snapshotCompleteTimeout().toMillis()
                        && !underway(track)) {
                    // a reset underway gives it up once done; else its snapshot complete is lost
                    counters.timedOut.incrementAndGet();
                    failed(
                            track,
                            active,
                            mono,
                            "no snapshot complete, nor anything on its way, within "
                                    + settings.snapshotCompleteTimeout());
                }
            }
            if (started) {
                long last = track.lastAliveAt != 0 ? track.lastAliveAt : startedAt;
                if (!track.inactive && mono - last > settings.maxInactivity().toMillis()) {
                    track.inactive = true;
                    openProducerGap(track, track.lastAliveMark);
                    // a producer that went silent while a recovery was in flight may have lost it
                    giveUp(track, mono, "the producer sent no alive for " + settings.maxInactivity(), false);
                    markDown(track, StatusCause.ALIVE_INTERVAL_VIOLATION);
                }
                checkDelay(track, mono);
            }
            maybeRequest(track, mono);
        }
        for (EventRecovery recovery : new ArrayList<>(eventRecoveries.values())) {
            String why = null;
            if (pastMaximum(recovery.askedMono, mono)) {
                why = "no snapshot complete within " + settings.maxRecoveryTime();
            } else if (!replies.containsKey(recovery.requestId)
                    && mono - recovery.heardAt
                            > settings.snapshotCompleteTimeout().toMillis()) {
                // accepted, and nothing of it heard since: the snapshot complete is lost. One the API has
                // not answered yet waits for its REST worker's deadlines
                why = "no snapshot complete, nor anything of it heard, within " + settings.snapshotCompleteTimeout();
            }
            if (why != null) {
                eventRecoveries.remove(recovery.requestId);
                endedWithoutCompleting(recovery);
                ended(recovery, EventRecoveryStatus.State.TIMED_OUT, why);
                counters.eventExpired.incrementAndGet();
                CompletableFuture<@Nullable Long> reply = replies.remove(recovery.requestId);
                if (reply != null) {
                    outbox.reply(reply, null);
                }
                LOG.debug("Event recovery {} of {}: {}", recovery.requestId, recovery.eventId, why);
            }
        }
        for (DeferredEvent event : new ArrayList<>(deferred)) {
            if (pastMaximum(event.deferredAt(), mono)) {
                deferred.remove(event);
                expired(event);
            }
        }
        statuses.expire(mono);
    }

    /**
     * A disabled producer sends the safety net no samples: a window of stale samples its lanes had
     * begun does not wait for it to be enabled again, and a session that lags stops lagging without it.
     */
    private void forgetStaleWindows(Track track) {
        boolean first = !track.disabledNoticed;
        track.disabledNoticed = true;
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            if (lane != null) {
                lane.above = false;
                if (first) {
                    // no sample of the producer will say it caught up, whether its lane was over the limit or not
                    stopLaggingIfCaughtUp(session);
                }
            }
        }
    }

    /** A session that lags stops when none of its lanes of an enabled producer is over the limit. */
    private void stopLaggingIfCaughtUp(SessionState session) {
        if (session.lagging
                && session.lanes.entrySet().stream()
                        .noneMatch(lane -> lane.getValue().above && enabled(track(lane.getKey())))) {
            session.lagging = false;
            LOG.info("Session {} caught up", session.info.id());
            events.lagging(session.info.id(), false);
        }
    }

    /** Whether a request the client made at {@code askedAt} has waited longer than the maximum recovery time. */
    private boolean pastMaximum(long askedAt, long mono) {
        return mono - askedAt > settings.maxRecoveryTime().toMillis();
    }

    /** A deferred event recovery whose reset never ended in time: its caller hears it was not accepted. */
    private void expired(DeferredEvent event) {
        counters.eventExpired.incrementAndGet();
        outbox.reply(event.reply(), null);
        long expired = expiredDeferred.count();
        if (Throttle.due(expired)) {
            LOG.warn(
                    "Event recovery of {} waited for a reset longer than {}, {} so far",
                    event.eventId(),
                    settings.maxRecoveryTime(),
                    expired);
        } else {
            LOG.debug(
                    "Event recovery of {} waited for a reset longer than {}",
                    event.eventId(),
                    settings.maxRecoveryTime());
        }
    }

    // ---- recoveries

    /** Asks for a recovery when the producer misses something and nothing holds the request back. */
    private void maybeRequest(Track track, long mono) {
        if (hasGaps(track) && canRequest(track, mono)) {
            if (track.capSpentAt != 0) {
                LOG.info("Recovery of producer {} re-armed after the cool-down", track.id);
                rearm(track);
            }
            request(track, mono);
        }
    }

    /**
     * Whether a recovery may go out now: none in flight, a session to receive it, the connection up,
     * every channel that receives it bound, an alive that says the producer is there - after the one
     * that gave the last recovery up - and its backoff and its cap's cool-down over.
     */
    private boolean canRequest(Track track, long mono) {
        return track.active == null && askable(track, mono);
    }

    /**
     * {@link #canRequest} apart from a recovery in flight: whether nothing else holds a request of
     * the producer back, as it will not once the recovery it has ends.
     */
    private boolean askable(Track track, long mono) {
        return started
                && connected
                && enabled(track)
                && receivers(track) > 0
                && aliveRecent(track, mono)
                && !track.awaitsAlive
                && !awaitsChannel(track)
                && mono >= track.retryAt
                && (track.capSpentAt == 0
                        || mono - track.capSpentAt >= settings.cooldown().toMillis());
    }

    private void request(Track track, long mono) {
        long wall = wall();
        long gapStart = oldestGap(track);
        long after = after(track, gapStart);
        long requestId = ids.next(this::inFlight);
        var active = new Active(requestId, ++sentSeq, after, wall, mono, producerNow(track), gapSeq, awaited(track));
        active.causes.add(track.cause);
        track.active = active;
        track.lastRequestId = requestId;
        counters.requested.incrementAndGet();
        if (track.failures > 0) {
            counters.reissued.incrementAndGet();
        }
        LOG.info(
                "Recovery {} of producer {} {}",
                requestId,
                track.id,
                after == 0 ? "as a full snapshot" : "from " + Instant.ofEpochMilli(after));
        if (after > gapStart) {
            clamped(track, requestId, gapStart, after);
        }
        outbox.request(new Outbox.Call.Snapshot(
                track.id, track.producer.getName(), requestId, after == 0 ? null : Instant.ofEpochMilli(after)));
    }

    /**
     * A recovery asked for from {@code after}, after the producer's stateful recovery window, not
     * from {@code gapStart}, where its oldest gap began: what the producer sent between them is not
     * recovered. A warning the first time for that gap; the re-issues of the same request, with the
     * window moving on, are debug.
     */
    private void clamped(Track track, long requestId, long gapStart, long after) {
        Object[] arguments = {
            requestId,
            track.id,
            Instant.ofEpochMilli(gapStart),
            track.producer.getStatefulRecoveryWindowInMinutes(),
            Instant.ofEpochMilli(after),
            Duration.ofMillis(after - gapStart)
        };
        if (track.clampWarnedFrom != gapStart) {
            track.clampWarnedFrom = gapStart;
            LOG.warn(CLAMPED, arguments);
        } else {
            LOG.debug(CLAMPED, arguments);
        }
    }

    /** The start of the oldest gap of the producer, or of any of its lanes. */
    private long oldestGap(Track track) {
        long start = track.gap == null ? Long.MAX_VALUE : track.gap.from;
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            if (lane != null && lane.gap != null) {
                start = Math.min(start, lane.gap.from);
            }
        }
        return start;
    }

    /**
     * Where a recovery starts: the oldest open gap, clamped to the producer's stateful recovery
     * window and capped at the producer's now; for none, 0.0.x's initial snapshot interval when
     * set, else 0 for a full snapshot.
     */
    private long after(Track track, long oldestGap) {
        return after(track, oldestGap, 0);
    }

    /** As above, with the window's start moved on by {@code margin}, in millis: for {@link #beyondWindow}. */
    private long after(Track track, long oldestGap, long margin) {
        long after = oldestGap;
        int window = track.producer.getStatefulRecoveryWindowInMinutes();
        if (after != 0 && window > 0) {
            // the window counts back from now by the producer's clock, as the gaps' starts are
            after = Math.max(
                    after, producerNow(track) - Duration.ofMinutes(window).toMillis() + margin);
        }
        return capped(track, after);
    }

    /**
     * {@code point}, but no later than the producer's now - the SDK's, corrected by the offset of
     * the producer's last alive: a timestamp from the producer's future, a component's fast clock
     * or a bad value, moves neither a recovery nor a resume point past what the producer has sent.
     * A point of 0, a full snapshot, or none stays as it is.
     */
    private long capped(Track track, long point) {
        return point > 0 ? Math.min(point, producerNow(track)) : point;
    }

    /**
     * The producer's clock now: the SDK's wall clock less the offset, as of this moment.
     */
    private long producerNow(Track track) {
        var now = clock.stamp();
        return now.wall() - offsetAt(track, now);
    }

    /**
     * The offset of the producer's clock, as of a reading. It was measured on an alive against the
     * SDK's wall clock, and a step of that clock since moves it: see {@link ClockOffset#at}. Before
     * the first alive there is no measure to correct.
     */
    private static long offsetAt(Track track, Stamp at) {
        if (track.lastAliveAt == 0) {
            return track.offset;
        }
        return ClockOffset.corrected(track.offset, track.skew, at);
    }

    private void accepted(Track track, Active active, long mono) {
        active.accepted = true;
        active.acceptedAt = mono;
        producers.setRecoveryInfo(
                track.id, new Recovery(active.after, active.issuedWall, active.requestId, settings.nodeId(), true));
        for (SessionState session : sessions.values()) {
            PendingReset pending = session.pending;
            if (pending == null || !asks(pending, track, active) || !pending.unaccepted.contains(track.id)) {
                continue;
            }
            Long disabled = disabledAmongUnaccepted(pending);
            if (disabled != null) {
                // the tick would cancel it too, but the answer came first: a reset made now is not the one asked for
                resetNotMadeForDisabledProducer(session, disabled, mono);
                continue;
            }
            pending.unaccepted.remove(track.id);
            if (active.awaited.isEmpty()) {
                // nothing waits for its snapshot complete, so it ends with its acceptance: that is no
                // reason to cancel the reset
                pending.endedOnAcceptance.add(track.id);
            }
            if (pending.unaccepted.isEmpty()) {
                resetOrCancel(session, pending, mono);
            }
        }
        if (track.active == active && active.awaited.isEmpty()) {
            // no session takes snapshot completes: nothing would ever complete it
            complete(track, active, mono, false);
        }
    }

    /**
     * Every session has seen the snapshot complete: the gaps it covers are closed. With gaps left
     * that opened since, one more recovery; with none, the producer is up.
     *
     * <p>A reset waiting for this recovery is cancelled, as for any recovery that ends before the
     * reset is made.
     */
    private void complete(Track track, Active active, long mono, boolean cancelsResets) {
        if (cancelsResets) {
            cancelResetsAskedFor(track, active, mono, true, null);
        }
        track.active = null;
        track.pausedUntil = mono;
        if (!active.accepted) {
            // the snapshot complete was quicker than the API's answer
            producers.setRecoveryInfo(
                    track.id, new Recovery(active.after, active.issuedWall, active.requestId, settings.nodeId(), true));
        }
        if (track.gap != null && track.gap.seq <= active.covers) {
            track.gap = null;
        }
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            Gap gap = lane == null ? null : lane.gap;
            // a session the transport is resetting keeps its gap: the reset drops what came since
            if (lane != null && gap != null && gap.seq <= active.covers && session.underway == 0) {
                lane.gap = null;
            }
            if (lane != null && active.seen.contains(session.info.id())) {
                // the snapshot is as of the request, and what came after was in the queue before it
                lane.checkpoint = Math.max(lane.checkpoint, active.requestedAt);
            }
        }
        track.failures = 0;
        track.retryAt = 0;
        track.capSpentAt = 0;
        counters.completed.incrementAndGet();
        track.everRecovered = true;
        LOG.info(
                "Recovery {} of producer {} completed in {} ms, for {}",
                active.requestId,
                track.id,
                mono - active.issuedMono,
                active.causes);
        if (hasDownGaps(track)) {
            maybeRequest(track, mono);
            return;
        }
        bringUp(track, StatusCause.RECOVERY_COMPLETED);
    }

    /**
     * The API did not accept the recovery, or it timed out: asked for again after the backoff, or,
     * with the cap spent, not before the cool-down.
     */
    private void failed(Track track, Active active, long mono, String reason) {
        ended(track, active, mono);
        producers.setRecoveryInfo(
                track.id, new Recovery(active.after, active.issuedWall, active.requestId, settings.nodeId(), false));
        counters.failed.incrementAndGet();
        cancelResetsAskedFor(track, active, mono, true, reason);
        if (!hasGaps(track)) {
            // nothing is missing, so nothing is asked for again, and nothing failed in a row
            track.failures = 0;
            upIfNothingMissing(track);
            return;
        }
        track.failures++;
        if (track.failures > settings.reissues()) {
            track.capSpentAt = mono;
            LOG.warn(
                    "Recovery {} of producer {} failed, {}; {} in a row, so the producer stays down for {}",
                    active.requestId,
                    track.id,
                    reason,
                    track.failures,
                    settings.cooldown());
            markDown(track, StatusCause.RECOVERY_FAILED);
        } else {
            track.retryAt = mono + backoff(settings.firstReissueBackoff(), track.failures);
            // the first failure of a streak is a warning; the rest, until a recovery or a cool-down ends it, are debug
            if (track.failures == 1) {
                LOG.warn(
                        "Recovery {} of producer {} failed, {}; asked for again in {} ms",
                        active.requestId,
                        track.id,
                        reason,
                        track.retryAt - mono);
            } else {
                LOG.debug(
                        "Recovery {} of producer {} failed, {}; asked for again in {} ms",
                        active.requestId,
                        track.id,
                        reason,
                        track.retryAt - mono);
            }
        }
    }

    /**
     * The recovery's snapshot will not come, through no fault of the request: lost with a queue, or
     * by the producer. It is no longer waited for, its gaps stay open, and no failure is counted.
     *
     * @param loss whether a lost connection, channel or queue is the reason: a reset it cancels is
     *     not held back by the net's backoff, as the loss says nothing against the net
     */
    private void giveUp(Track track, long mono, String reason, boolean loss) {
        Active active = track.active;
        if (active == null) {
            return;
        }
        ended(track, active, mono);
        counters.abandoned.incrementAndGet();
        LOG.info("Recovery {} of producer {} given up: {}", active.requestId, track.id, reason);
        cancelResetsAskedFor(track, active, mono, !loss, null);
    }

    /** Whether the reset asked for exactly this recovery of the producer. */
    private static boolean asks(PendingReset pending, Track track, Active active) {
        Long requestId = pending.asked.get(track.id);
        return requestId != null && requestId == active.requestId;
    }

    /**
     * A recovery ended, by whatever way - failed, given up, completed - and a reset that asked for
     * it will not be made: what it would drop is no longer repeated by the recovery the reset waited
     * for. This is the one place that cancels a reset for the end of a recovery.
     *
     * @param backoff whether the net waits before it tries again
     * @param failure why the API did not take it, for a refusal or a timeout; null otherwise
     */
    private void cancelResetsAskedFor(
            Track track, Active active, long mono, boolean backoff, @Nullable String failure) {
        for (SessionState session : sessions.values()) {
            PendingReset pending = session.pending;
            if (pending == null || !asks(pending, track, active)) {
                continue;
            }
            cancelReset(session, mono, backoff);
            if (failure != null) {
                counters.resetRequestsFailed.incrementAndGet();
                LOG.warn(
                        "The safety net did not reset session {}: recovery {} of producer {} failed, {}",
                        session.info.id(),
                        active.requestId,
                        track.id,
                        failure);
                events.safetyNetRequestFailed(session.info.id(), track.id, failure);
            }
        }
    }

    /**
     * The recovery is no longer waited for, without completing: what it sent may still be on its
     * way, ahead of the snapshot of the next one asked for.
     */
    private void ended(Track track, Active active, long mono) {
        track.active = null;
        track.pausedUntil = mono;
        remember(track.ended, new Ended(active.requestId, active.seq));
    }

    /** The event recovery ended without completing: what it sent may still be ahead of a later one. */
    private void endedWithoutCompleting(EventRecovery recovery) {
        var track = tracks.get(recovery.producerId);
        if (track != null) {
            remember(track.endedEvents, new Ended(recovery.requestId, recovery.seq));
        }
    }

    private static void remember(ArrayDeque<Ended> ended, Ended one) {
        ended.addFirst(one);
        if (ended.size() > ENDED_REMEMBERED) {
            ended.removeLast();
        }
    }

    private void rearm(Track track) {
        track.failures = 0;
        track.capSpentAt = 0;
        track.retryAt = 0;
    }

    private void eventCompleted(EventRecovery recovery) {
        eventRecoveries.remove(recovery.requestId);
        ended(recovery, EventRecoveryStatus.State.COMPLETED, null);
        CompletableFuture<@Nullable Long> reply = replies.remove(recovery.requestId);
        if (reply != null) {
            // its snapshot complete came before the API's answer, and says the API took it
            outbox.reply(reply, recovery.requestId);
        }
        LOG.info("Event recovery {} of {} completed", recovery.requestId, recovery.eventId);
        events.eventRecoveryCompleted(recovery.producerId, recovery.eventId, recovery.requestId);
    }

    /** The event recovery's status says it ended, now, in {@code state}. */
    private void ended(EventRecovery recovery, EventRecoveryStatus.State state, @Nullable String reason) {
        statuses.ended(recovery.requestId, state, Instant.ofEpochMilli(wall()), reason, mono());
    }

    // ---- gaps and checkpoints

    /**
     * A gap from {@code from}, joined with the one open: the earlier start, and a new number, since
     * what is missing now is missing after any recovery already asked for.
     */
    private Gap openGap(@Nullable Gap open, long from, boolean pendingReset) {
        Duration initial = settings.initialSnapshotInterval();
        if (from == 0 && initial != null) {
            // fixed now, so asking again does not move it
            from = wall() - initial.toMillis();
        }
        if (open == null) {
            return new Gap(from, ++gapSeq, pendingReset);
        }
        open.from = Math.min(open.from, from);
        open.seq = ++gapSeq;
        open.pendingReset &= pendingReset;
        return open;
    }

    /**
     * The producer stopped sending: every session misses what came after its last subscribed alive,
     * or after {@code fallback} when there was none. Without either, nothing was received to miss.
     */
    private void openProducerGap(Track track, long fallback) {
        long from = track.safePoint > 0 ? track.safePoint : fallback;
        if (from > 0) {
            track.gap = openGap(track.gap, from, false);
        }
    }

    /**
     * Where the producer's recovery would start now, for a session that opens: its resume point,
     * else, with no session to have one, the last subscribed alive, else the client's start.
     */
    private long recoveryPoint(Track track) {
        long point = resumePoint(track);
        if (point != NO_POINT) {
            return point;
        }
        return track.safePoint > 0 ? track.safePoint : seed(track);
    }

    /**
     * Where a recovery of the producer would have to start now for no session to miss anything:
     * the oldest of the open gaps' starts, the producer's and the sessions', and of the checkpoints
     * of the sessions that receive it - a session with no gap still has what it has not processed
     * only in its queue. Unclamped, so a client that resumes from it misses nothing; {@link
     * #NO_POINT} when no session receives the producer.
     */
    private long resumePoint(Track track) {
        long point = track.gap == null ? Long.MAX_VALUE : track.gap.from;
        boolean any = false;
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            if (lane != null) {
                any = true;
                point = Math.min(point, lane.gap == null ? lane.checkpoint : Math.min(lane.gap.from, lane.checkpoint));
            }
        }
        return any ? point : NO_POINT;
    }

    /**
     * Publishes each producer's resume point that moved, for {@link
     * Producer#getTimestampForRecovery()}: the actor calls it after every fact. Nothing before the
     * start, when the client's own start stands; a producer no session receives keeps what it had;
     * once the feed is {@link #closing}, only a point that goes back - and a gap of the producer's
     * opened after its last session closed still takes it back. Each is capped at the producer's
     * now, so a client that persists it never resumes from the producer's future.
     */
    void publishResumePoints() {
        if (!started) {
            return;
        }
        for (Track track : tracks.values()) {
            long point = resumePoint(track);
            Gap gap = track.gap;
            if (point == NO_POINT && closing && gap != null) {
                // every session closed at shutdown, before what the producer's gap says they
                // missed: the point the client keeps goes back to its start all the same; one
                // never published stays so, since NO_POINT is below any point
                point = Math.min(track.published, gap.from);
            }
            point = capped(track, point);
            if (point != NO_POINT
                    && point != track.published
                    && (!closing || track.published == NO_POINT || point < track.published)) {
                track.published = point;
                producers.setResumePoint(track.id, point);
            }
        }
    }

    /**
     * The client's recovery start, epoch millis, or 0 for a full snapshot: what the client set, not
     * what {@link Producer#getTimestampForRecovery()} reports, which follows the feed as it runs.
     */
    private long seed(Track track) {
        return producers.recoveryFrom(track.id);
    }

    private boolean hasGaps(Track track) {
        if (track.gap != null) {
            return true;
        }
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            if (lane != null && lane.gap != null) {
                return true;
            }
        }
        return false;
    }

    /** Gaps that keep the producer down: all but those of a reset the safety net has not made yet. */
    private boolean hasDownGaps(Track track) {
        if (track.gap != null) {
            return true;
        }
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            Gap gap = lane == null ? null : lane.gap;
            if (gap != null && !gap.pendingReset) {
                return true;
            }
        }
        return false;
    }

    // ---- the safety net

    private void sample(SessionState session, Lane lane, Track track, long generatedAt, Stamp taken) {
        long mono = mono();
        if (!offsetFresh(track, mono)) {
            lane.age = UNKNOWN_AGE;
            lane.above = false;
            return;
        }
        long age = taken.wall() - generatedAt - offsetAt(track, taken);
        lane.age = age;
        if (track.active != null
                || hasGaps(track)
                || session.pending != null
                || session.underway != 0
                || taken.mono() <= pausedUntil(track)) {
            // a recovery's snapshot ahead of a live message makes it old, on every session
            lane.above = false;
            return;
        }
        if (age <= settings.staleLimit().toMillis()) {
            lane.above = false;
            stopLaggingIfCaughtUp(session);
            return;
        }
        if (!lane.above || lane.aboveSince <= pausedUntil(track)) {
            // a window that began before the recovery ended, or before the offset was fresh again, does not count that
            // time
            lane.above = true;
            lane.aboveSince = taken.mono();
        } else if (taken.mono() - lane.aboveSince >= settings.staleWindow().toMillis()) {
            trigger(session, track, age, mono);
        }
    }

    /**
     * Samples taken up to this time are not the safety net's: a recovery of the producer was in
     * flight, or its clock offset was stale and an alive has made it fresh again.
     */
    private static long pausedUntil(Track track) {
        return Math.max(track.pausedUntil, track.offsetFreshAt);
    }

    /**
     * The session has been too far behind for the window: a recovery for each of its enabled
     * producers first, the reset once the API has accepted them all. Spent, the session is lagging
     * instead.
     *
     * <p>The reset drops the backlog of every producer the session receives, and what it drops of a
     * producer is repeated only by the recovery asked for after it. So the net starts only when all
     * of them can be asked for now, and asked for again right after the reset: none with a recovery
     * in flight, in its backoff or its cool-down, silent, or missing a bound queue of any session,
     * and none with a gap open, the producer's or a session's: its ordinary recovery asks from the
     * older gap, not from the checkpoint the window check compares, so it goes first. Until then
     * the net waits, with no request, no lagging and no backoff, and a later stale sample tries
     * again. The cap and the cool-down count failures, not requests: a request that is
     * accepted spends none of it, and one that fails ends the reset before it is made.
     */
    private void trigger(SessionState session, Track track, long age, long mono) {
        if (!enabled(track)) {
            // a client callback disabled the producer on the very sample: it says nothing of the others
            return;
        }
        if (resetsWithinCooldown(session, mono) >= settings.resets()) {
            if (!session.lagging) {
                session.lagging = true;
                LOG.warn(
                        "Session {} is {} ms behind producer {}, with the safety net's {} resets spent: it lags",
                        session.info.id(),
                        age,
                        track.id,
                        settings.resets());
                events.lagging(session.info.id(), true);
            }
            return;
        }
        if (mono < session.nextResetAt || session.underway != 0) {
            return;
        }
        var lanes = new ArrayList<Map.Entry<Long, Lane>>();
        for (Map.Entry<Long, Lane> entry : session.lanes.entrySet()) {
            Track other = track(entry.getKey());
            if (!enabled(other)) {
                continue;
            }
            if (!canRequest(other, mono) || hasGaps(other)) {
                return;
            }
            lanes.add(entry);
        }
        if (lanes.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, Lane> entry : lanes) {
            if (aheadOfProducer(track(entry.getKey()), entry.getValue())) {
                // the producer's clock stepped back: a recovery is asked from its now, and a reset would drop
                // what is queued after it; the checkpoint is behind it again when the clock has caught up
                return;
            }
        }
        for (Map.Entry<Long, Lane> entry : lanes) {
            Track other = track(entry.getKey());
            if (beyondWindow(other, entry.getValue())) {
                laggingBeyondWindow(session, other, age, track);
                return;
            }
        }
        var unaccepted = new LinkedHashSet<Long>();
        for (Map.Entry<Long, Lane> entry : lanes) {
            Lane lane = entry.getValue();
            lane.gap = openGap(lane.gap, lane.checkpoint, true);
            unaccepted.add(entry.getKey());
        }
        var pending = new PendingReset(unaccepted, track.id, age, ++resetSeq);
        session.pending = pending;
        LOG.warn(
                "Session {} is {} ms behind producer {}: the safety net asks for recoveries before it resets",
                session.info.id(),
                age,
                track.id);
        for (Long producerId : unaccepted) {
            Track other = track(producerId);
            maybeRequest(other, mono);
            Active active = other.active;
            if (active == null) {
                // disabled since the check above: it got no request, and none will answer for it
                resetNotMadeForDisabledProducer(session, producerId, mono);
                return;
            }
            pending.asked.put(producerId, active.requestId);
        }
    }

    /** A reset waiting for a producer that has since been disabled would wait for ever: it is given up. */
    private void cancelResetsWaitingForDisabledProducers(long mono) {
        for (SessionState session : sessions.values()) {
            PendingReset pending = session.pending;
            if (pending == null) {
                continue;
            }
            Long disabled = disabledAmongUnaccepted(pending);
            if (disabled != null) {
                resetNotMadeForDisabledProducer(session, disabled, mono);
            }
        }
    }

    /** A producer the reset still waits for that has been disabled since, or null for none. */
    private @Nullable Long disabledAmongUnaccepted(PendingReset pending) {
        for (Long producerId : pending.unaccepted) {
            if (!enabled(track(producerId))) {
                return producerId;
            }
        }
        return null;
    }

    private void resetNotMadeForDisabledProducer(SessionState session, long producerId, long mono) {
        cancelReset(session, mono, true);
        counters.resetRequestsFailed.incrementAndGet();
        LOG.warn(
                "The safety net did not reset session {}: producer {} was disabled before its recovery was accepted",
                session.info.id(),
                producerId);
        events.safetyNetRequestFailed(session.info.id(), producerId, "producer disabled");
    }

    /**
     * Whether a recovery for what the lane misses would start after the lane's checkpoint: the
     * producer's stateful recovery window has moved past it, so the messages between are repeated
     * by no recovery, and a reset would drop them for good, the producer's backlog in the session's
     * queue included, which the session asks for after the reset. The start is the one {@link #request}
     * computes; a checkpoint of 0 is a full snapshot's or none, and the recovery then covers it.
     *
     * <p>A checkpoint within {@link #WINDOW_MARGIN} of the window's start counts as beyond it, the
     * margin at most half the window. It is for the time from the acceptance of the recoveries to the
     * recovery asked into the new queue, which is clamped later, when the window has moved on; it is
     * a margin for that time, not a proof that the checkpoint stays inside.
     */
    private boolean beyondWindow(Track track, Lane lane) {
        long checkpoint = lane.checkpoint;
        long window = Duration.ofMinutes(track.producer.getStatefulRecoveryWindowInMinutes())
                .toMillis();
        long margin = Math.clamp(window / 2, 0, WINDOW_MARGIN.toMillis());
        return checkpoint != 0 && checkpoint < after(track, Math.min(oldestGap(track), checkpoint), margin);
    }

    /**
     * Whether the lane's checkpoint lies after the producer's now, which a recovery is capped at: the
     * producer's clock stepped back after the session took messages stamped by it, and a reset would
     * drop what is queued between.
     */
    private boolean aheadOfProducer(Track track, Lane lane) {
        return lane.checkpoint > producerNow(track);
    }

    /** The session is too far behind for a reset to be safe: it lags instead, once per episode, and nothing is asked. */
    private void laggingBeyondWindow(SessionState session, Track producer, long age, Track stale) {
        if (session.lagging) {
            return;
        }
        session.lagging = true;
        LOG.warn(
                "Session {} is {} ms behind producer {}, and its messages of producer {} reach back beyond the"
                        + " producer's stateful recovery window of {} minutes: a reset would drop what no recovery"
                        + " repeats, so it lags",
                session.info.id(),
                age,
                stale.id,
                producer.id,
                producer.producer.getStatefulRecoveryWindowInMinutes());
        events.lagging(session.info.id(), true);
    }

    /**
     * Every recovery the reset waited for is accepted. The state is looked at again, as it is now:
     * the session's channel is replaced only if each enabled producer of the session still has, as
     * its recovery in flight, the one the net asked for, and the API has accepted it - or it ended
     * with its acceptance, with no session to wait for; each can still be asked for after the reset
     * - an alive is recent and none is awaited, no retry or cool-down holds it back, every session
     * that receives it has a bound queue, as {@link #canRequest} says apart from the recovery in
     * flight, and none has a gap open apart from the net's own, which its ordinary recovery asks from;
     * the feed is not closing, which would drop what the close is about to settle; and the
     * checkpoint of each is not after the producer's clock and has not fallen out of its stateful
     * recovery window meanwhile, which no recovery would then repeat.
     */
    private void resetOrCancel(SessionState session, PendingReset pending, long mono) {
        if (closing) {
            LOG.debug("The safety net did not reset session {}: the feed is closing", session.info.id());
            cancelReset(session, mono, true);
            return;
        }
        for (Map.Entry<Long, Lane> entry : session.lanes.entrySet()) {
            long producerId = entry.getKey();
            Track other = track(producerId);
            if (!enabled(other)) {
                continue;
            }
            Long requestId = pending.asked.get(producerId);
            if (requestId == null) {
                resetNotMadeAtAcceptance(
                        session,
                        producerId,
                        mono,
                        true,
                        "producer enabled since",
                        "producer " + producerId + " was enabled after the net asked for its recoveries");
                return;
            }
            Active active = other.active;
            // one that ended with its acceptance counts only while it is the producer's latest recovery
            boolean asked = active == null
                    ? pending.endedOnAcceptance.contains(producerId) && other.lastRequestId == requestId
                    : active.requestId == requestId && active.accepted;
            if (!asked) {
                resetNotMadeAtAcceptance(
                        session,
                        producerId,
                        mono,
                        true,
                        "recovery changed",
                        "the recovery it asked of producer " + producerId + " is not the one in flight");
                return;
            }
            if (hasDownGaps(other)) {
                // a gap opened since - the producer stopped sending, a session lost its queue - which the ordinary
                // recovery asks from, older than what the checkpoints say: it goes first, and the reset waits
                resetNotMadeAtAcceptance(
                        session,
                        producerId,
                        mono,
                        true,
                        "producer has a gap open",
                        "producer " + producerId
                                + " has a gap open since the net asked, which its ordinary recovery has to repeat first");
                return;
            }
            if (!askable(other, mono)) {
                resetNotMadeAtAcceptance(
                        session,
                        producerId,
                        mono,
                        true,
                        "producer cannot be asked",
                        "producer " + producerId
                                + " could not be asked for a recovery after the reset: it is silent, waits to be asked"
                                + " again, or a session that receives it has no queue bound");
                return;
            }
            if (aheadOfProducer(other, entry.getValue())) {
                resetNotMadeAtAcceptance(
                        session,
                        producerId,
                        mono,
                        true,
                        "checkpoint ahead of the producer",
                        "its checkpoint of producer " + producerId
                                + " lies after the producer's clock, which a recovery is not asked from");
                return;
            }
            if (beyondWindow(other, entry.getValue())) {
                resetNotMadeAtAcceptance(
                        session,
                        producerId,
                        mono,
                        true,
                        "beyond the recovery window",
                        "its messages of producer " + producerId
                                + " reach back beyond the producer's stateful recovery window, which moved on"
                                + " while the recovery was accepted");
                return;
            }
        }
        reset(session, pending);
    }

    private void resetNotMadeAtAcceptance(
            SessionState session, long producerId, long mono, boolean backoff, String reason, String explanation) {
        cancelReset(session, mono, backoff);
        counters.resetRequestsFailed.incrementAndGet();
        LOG.warn("The safety net did not reset session {}: {}", session.info.id(), explanation);
        events.safetyNetRequestFailed(session.info.id(), producerId, reason);
    }

    /**
     * Every recovery the reset waited for is accepted: the session's channel is replaced. The
     * reset stays pending until the transport reports it done, and meanwhile the session's snapshot
     * completes are ignored: they come from the queue being replaced. Its producers' recoveries are
     * in flight all the while, so nothing more is asked for them.
     */
    private void reset(SessionState session, PendingReset pending) {
        session.pending = null;
        session.underway = pending.number;
        session.underwayProducer = pending.producerId;
        session.underwayAge = pending.age;
        session.underwaySeq = sentSeq;
        for (Lane lane : session.lanes.values()) {
            Gap gap = lane.gap;
            if (gap != null) {
                gap.pendingReset = false;
            } else {
                // what the reset drops starts here, before any sample of the new channel moves it
                lane.gap = openGap(null, lane.checkpoint, false);
            }
            lane.above = false;
        }
        LOG.warn(
                "The safety net resets session {}, {} ms behind producer {}",
                session.info.id(),
                pending.age,
                pending.producerId);
        // every producer is down before the outbox hears of the reset
        for (Long producerId : session.lanes.keySet()) {
            markDown(track(producerId), StatusCause.SAFETY_NET_RESET);
        }
        outbox.reset(session.info.id(), pending.number);
    }

    /**
     * How many resets the session made within the cool-down, the older ones dropped: what the cap
     * and the backoff count, so every backoff is computed after this.
     */
    private int resetsWithinCooldown(SessionState session, long mono) {
        while (!session.resets.isEmpty()
                && mono - session.resets.getFirst() >= settings.cooldown().toMillis()) {
            session.resets.removeFirst();
        }
        return session.resets.size();
    }

    /**
     * A reset that will not be made: its gaps close, and the net waits before it tries again, unless
     * {@code backoff} is false, as for a loss.
     */
    private void cancelReset(SessionState session, long mono, boolean backoff) {
        session.pending = null;
        if (backoff) {
            session.nextResetAt = mono + backoff(settings.firstResetBackoff(), resetsWithinCooldown(session, mono) + 1);
        }
        for (Lane lane : session.lanes.values()) {
            Gap gap = lane.gap;
            if (gap != null && gap.pendingReset) {
                lane.gap = null;
                // a gap opened and closed with no recovery of this lane between: the window's time was not its own
                lane.above = false;
            }
        }
    }

    // ---- producer status

    /**
     * Whether a session processes the producer late: its last sample older than the maximum
     * inactivity, or nothing taken for that long while alives say the producer sends. A session
     * that has taken nothing yet counts as on time. Late takes an up producer down, without a
     * recovery, as in 0.0.x; on time again brings it back.
     */
    private void checkDelay(Track track, long mono) {
        long max = settings.maxInactivity().toMillis();
        boolean delayed = false;
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            if (lane != null
                    && ((lane.age != UNKNOWN_AGE && lane.age > max && offsetFresh(track, mono))
                            || (lane.lastAt != 0 && mono - lane.lastAt > max && aliveRecent(track, mono)))) {
                delayed = true;
            }
        }
        track.delayed = delayed;
        if (delayed && !track.down) {
            markDown(track, StatusCause.PROCESSING_DELAY);
        } else if (!delayed && track.down && track.cause == StatusCause.PROCESSING_DELAY) {
            markUp(track, StatusCause.DELAY_STABILIZED);
        }
    }

    /**
     * A producer down only for gaps that went away with a closed session, or for a lost connection
     * that no session it has now missed anything of, comes back.
     */
    private void upIfNothingMissing(Track track) {
        if (track.down
                && connected
                && track.everRecovered
                && track.active == null
                && track.cause != StatusCause.PROCESSING_DELAY
                && !hasDownGaps(track)) {
            // nothing is missing: what failed before does not count against what comes next
            rearm(track);
            bringUp(track, StatusCause.RECOVERY_COMPLETED);
        }
    }

    /**
     * Nothing is missing any more: the producer is up, unless a session processes it late, which
     * keeps it down for that until the session catches up.
     */
    private void bringUp(Track track, StatusCause cause) {
        if (track.delayed) {
            markDown(track, StatusCause.PROCESSING_DELAY);
        } else {
            markUp(track, cause);
        }
    }

    /**
     * Down for {@code cause}: reported at the cause's level when the producer was up or down for
     * another cause, and to 0.0.x's callback only when it was up or down for another public reason.
     */
    private void markDown(Track track, StatusCause cause) {
        if (!enabled(track) || (track.down && track.cause == cause)) {
            return;
        }
        Active active = track.active;
        if (active != null) {
            active.causes.add(cause);
        }
        // 0.0.x's callback fires when the down flag or the public reason changes, not for a cause alone
        boolean publicChange = !track.down || track.cause.reason() != cause.reason();
        track.down = true;
        track.cause = cause;
        producers.setDown(track.id, true);
        LOG.info("Producer {} down: {}", track.id, cause.description());
        tell(new ProducerStatusChange(track.id, true, track.delayed, cause, wall()), publicChange);
    }

    /**
     * Up for {@code cause}; the first time since the feed opened, whatever brought it up, it is up
     * for its first recovery, which the client hears of once per producer, as in 0.0.x.
     */
    private void markUp(Track track, StatusCause given) {
        if (!enabled(track) || !track.down) {
            return;
        }
        StatusCause cause = track.reportedUp ? given : StatusCause.FIRST_RECOVERY_COMPLETED;
        track.reportedUp = true;
        track.down = false;
        track.cause = cause;
        producers.setDown(track.id, false);
        LOG.info("Producer {} up: {}", track.id, cause.description());
        tell(new ProducerStatusChange(track.id, false, track.delayed, cause, wall()), true);
    }

    /** A status change: the cause-level event always, the public one when the flag or reason changed. */
    private void tell(ProducerStatusChange change, boolean publicChange) {
        if (publicChange) {
            producers.statusTold(
                    change.producerId(), change.down(), change.delayed(), change.reason(), change.timestamp());
            events.producerStatus(change);
        }
        events.producerCause(change);
    }

    // ---- small things

    /** Whether a session that receives the producer has a reset with the transport. */
    private boolean underway(Track track) {
        for (SessionState session : sessions.values()) {
            if (session.underway != 0 && session.lanes.containsKey(track.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a session that receives the producer has no bound queue to send to yet: its reset is
     * with the transport, or its lost channel has not been opened again.
     */
    private boolean awaitsChannel(Track track) {
        for (SessionState session : sessions.values()) {
            if ((session.underway != 0 || session.lost) && session.lanes.containsKey(track.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives up the event recoveries whose snapshot complete went with a lost queue: every one, for
     * {@code session} null, else those waiting for that session. Whoever still waits for the API's
     * answer hears null.
     */
    private void giveUpEvents(@Nullable Integer session) {
        for (EventRecovery recovery : new ArrayList<>(eventRecoveries.values())) {
            if (session == null || (recovery.awaited.contains(session) && !recovery.seen.contains(session))) {
                eventRecoveries.remove(recovery.requestId);
                endedWithoutCompleting(recovery);
                ended(recovery, EventRecoveryStatus.State.FAILED, "its snapshot went with a lost queue");
                counters.eventAbandoned.incrementAndGet();
                CompletableFuture<@Nullable Long> reply = replies.remove(recovery.requestId);
                if (reply != null) {
                    outbox.reply(reply, null);
                }
                LOG.debug(
                        "Event recovery {} of {} given up: its snapshot went with a lost queue",
                        recovery.requestId,
                        recovery.eventId);
            }
        }
    }

    private boolean aliveRecent(Track track, long mono) {
        return track.lastAliveAt != 0
                && !track.inactive
                && mono - track.lastAliveAt <= settings.maxInactivity().toMillis();
    }

    /** Whether the offset is recent enough to correct ages with: an alive within two intervals. */
    private boolean offsetFresh(Track track, long mono) {
        return track.lastAliveAt != 0 && mono - track.lastAliveAt <= 2 * track.aliveInterval;
    }

    private boolean enabled(Track track) {
        return producers.isProducerEnabled(track.id);
    }

    private int receivers(Track track) {
        int receivers = 0;
        for (SessionState session : sessions.values()) {
            if (session.lanes.containsKey(track.id)) {
                receivers++;
            }
        }
        return receivers;
    }

    /** The sessions a recovery of the producer waits for: those that receive it and take completions. */
    private Set<Integer> awaited(Track track) {
        var awaited = new HashSet<Integer>();
        for (SessionState session : sessions.values()) {
            if (session.info.takesSnapshotComplete() && session.lanes.containsKey(track.id)) {
                awaited.add(session.info.id());
            }
        }
        return awaited;
    }

    /** Whether a recovery or an event recovery of this request id has not ended; the actor's thread only. */
    boolean inFlight(long requestId) {
        if (eventRecoveries.containsKey(requestId) || replies.containsKey(requestId)) {
            return true;
        }
        for (Track track : tracks.values()) {
            Active active = track.active;
            if (active != null && active.requestId == requestId) {
                return true;
            }
        }
        return false;
    }

    /** The track of a producer of the list, or null, counted, for one the list does not have. */
    private @Nullable Track known(long producerId) {
        Track track = tracks.get(producerId);
        if (track == null) {
            long unknown = counters.unknownProducers.incrementAndGet();
            // the first, then one in a thousand: a feed of such a producer would flood the log
            if (unknown == 1 || unknown % 1_000 == 0) {
                LOG.warn(
                        "A fact about producer {}, which the producer list does not have, is ignored, {} so far",
                        producerId,
                        unknown);
            }
        }
        return track;
    }

    /** The track of a producer a session or a request already named. */
    private Track track(long producerId) {
        Track track = tracks.get(producerId);
        if (track == null) {
            throw new IllegalStateException("no producer " + producerId);
        }
        return track;
    }

    private static long backoff(Duration first, int attempt) {
        return first.toMillis() << Math.clamp(attempt - 1, 0, 20);
    }

    /** Millis on the monotonic clock: for every duration between two local events, never published. */
    private long mono() {
        return clock.monoMillis();
    }

    /** Epoch millis by the SDK's wall clock: for what is published, and for comparisons with a producer's clock. */
    private long wall() {
        return clock.wallMillis();
    }

    // ---- what is read for tests and for getHealth()

    /** Whether the session is lagging, with the safety net's resets spent. */
    boolean lagging(int id) {
        SessionState session = sessions.get(id);
        return session != null && session.lagging;
    }

    /** The request id of the producer's recovery in flight, or 0 for none. */
    long inFlightRecovery(long producerId) {
        Active active = track(producerId).active;
        return active == null ? 0 : active.requestId;
    }

    /** The session's checkpoint for the producer, epoch millis, or -1 when it does not receive it. */
    long checkpoint(int id, long producerId) {
        SessionState session = sessions.get(id);
        Lane lane = session == null ? null : session.lanes.get(producerId);
        return lane == null ? -1 : lane.checkpoint;
    }

    // ---- the state

    /** One producer, as the actor tracks it. */
    private static final class Track {
        final long id;
        final Producer producer;
        boolean down = true;
        StatusCause cause = StatusCause.STARTING;
        boolean delayed;
        boolean everRecovered;
        /** Whether it has been reported up since the feed opened. */
        boolean reportedUp;
        /** When the last alive arrived, on the monotonic clock; 0 before the first. */
        long lastAliveAt;

        /** The last alive's mark: where a gap starts for a producer that has no safe point. */
        long lastAliveMark;
        /** The last subscribed alive's mark: the producer has sent everything before it. */
        long safePoint;
        /** The SDK's wall clock minus the producer's, measured on the last alive. */
        long offset;
        /** The SDK's wall clock minus its monotonic one, at the reading the offset was measured on. */
        long skew;
        /** How often the producer sends an alive, in millis, a moving average. */
        long aliveInterval;
        /** Whether the producer went too long without an alive, and no alive has come since. */
        boolean inactive;
        /** Whether an unsubscribed alive gave the last recovery up, and no alive has come since. */
        boolean awaitsAlive;
        /** What every session misses because the producer stopped sending; null for nothing. */
        @Nullable
        Gap gap;
        /** The start of the gap whose recovery was last clamped to the stateful window and warned of; 0 for none. */
        long clampWarnedFrom;
        /** The resume point last published, or {@link #NO_POINT}. */
        long published = NO_POINT;

        @Nullable
        Active active;
        /** Whether the tick has seen the producer disabled since it was last enabled. */
        boolean disabledNoticed;
        /** The request id of its latest recovery, in flight or ended; 0 before the first. */
        long lastRequestId;
        /**
         * The request ids of its last recoveries that failed or were given up, newest first, at most
         * {@code ENDED_REMEMBERED}: what they sent may still be ahead of the one in flight.
         */
        final ArrayDeque<Ended> ended = new ArrayDeque<>();
        /**
         * Its last event recoveries that failed, expired or were given up, newest first, at most
         * {@code ENDED_REMEMBERED}: what they sent may still be ahead of the one in flight, or of
         * an event recovery asked for later.
         */
        final ArrayDeque<Ended> endedEvents = new ArrayDeque<>();
        /** Samples taken up to this time are not the safety net's: a recovery was in flight. */
        long pausedUntil;
        /** When an alive made the clock offset fresh again after it was stale, monotonic; 0 before the first alive. */
        long offsetFreshAt;
        /** Recoveries failed in a row. */
        int failures;

        long retryAt;
        /** When the cap was spent, 0 while it is not. */
        long capSpentAt;

        Track(Producer producer, long aliveInterval) {
            this.id = producer.getId();
            this.producer = producer;
            this.aliveInterval = aliveInterval;
        }
    }

    /** One session, as the actor tracks it. */
    private static final class SessionState {
        final SessionInfo info;
        /** The producers it receives, by id. */
        final Map<Long, Lane> lanes = new LinkedHashMap<>();
        /** When the safety net reset it, within the cool-down, oldest first. */
        final ArrayDeque<Long> resets = new ArrayDeque<>();

        long nextResetAt;
        boolean lagging;

        @Nullable
        PendingReset pending;
        /** The number of the reset the transport is making, 0 for none. */
        long underway;
        /** Whether its channel was lost and no new one is bound yet. */
        boolean lost;
        /** The producer whose messages were too old, and how old, for the reset underway. */
        long underwayProducer;

        long underwayAge;
        /** The last request sent before the reset underway was made: what it dropped was asked for up to it. */
        long underwaySeq;

        SessionState(SessionInfo info) {
            this.info = info;
        }
    }

    /** One session's view of one producer. */
    private static final class Lane {
        /** The running maximum of what it finished; epoch millis, 0 for nothing. */
        long checkpoint;

        @Nullable
        Gap gap;
        /** The last sample's age, or {@link #UNKNOWN_AGE}. */
        long age = UNKNOWN_AGE;
        /** When the session last took a message or an alive of the producer, monotonic; 0 before the first. */
        long lastAt;
        /** Whether the samples have been over the stale limit since {@link #aboveSince}. */
        boolean above;

        long aboveSince;
    }

    /** What a session misses of a producer, from {@code from}, 0 for everything. */
    private static final class Gap {
        long from;
        /** The gap's number; a recovery covers the gaps numbered up to the last at its start. */
        long seq;
        /** Whether it is the safety net's, for a reset not made yet. */
        boolean pendingReset;

        Gap(long from, long seq, boolean pendingReset) {
            this.from = from;
            this.seq = seq;
            this.pendingReset = pendingReset;
        }
    }

    /** A producer's recovery in flight. */
    private static final class Active {
        final long requestId;
        /** Its place in the order requests were sent in, {@link #sentSeq}. */
        final long seq;
        /** Epoch millis, 0 for a full snapshot. */
        final long after;

        /** When it was asked for, by the SDK's wall clock: what {@link Recovery} publishes. */
        final long issuedWall;
        /** When it was asked for, on the monotonic clock: what its deadlines count from. */
        final long issuedMono;
        /** When it was asked for, by the producer's clock. */
        final long requestedAt;
        /** The number of the last gap it covers. */
        final long covers;
        /** The sessions whose snapshot complete it waits for. */
        final Set<Integer> awaited;

        final Set<Integer> seen = new HashSet<>();
        /** Why it was asked for, and what joined it since. */
        final Set<StatusCause> causes = EnumSet.noneOf(StatusCause.class);

        boolean accepted;
        /** When the API accepted it, or a snapshot complete said so, on the monotonic clock. */
        long acceptedAt;
        /**
         * When a session that awaits it last took something that says it is on its way, or when it
         * was asked for: its snapshot complete is waited for this long after, at most.
         */
        long heardAt;

        Active(
                long requestId,
                long seq,
                long after,
                long issuedWall,
                long issuedMono,
                long requestedAt,
                long covers,
                Set<Integer> awaited) {
            this.requestId = requestId;
            this.seq = seq;
            this.after = after;
            this.issuedWall = issuedWall;
            this.issuedMono = issuedMono;
            this.requestedAt = requestedAt;
            this.covers = covers;
            this.awaited = awaited;
            this.heardAt = issuedMono;
        }
    }

    /** An event recovery in flight. */
    private static final class EventRecovery {
        final long requestId;
        /** Its place in the order requests were sent in, {@link #sentSeq}. */
        final long seq;

        final long producerId;
        final URN eventId;
        /** When it was sent, by the SDK's wall clock: what its status publishes. */
        final long issuedWall;
        /**
         * When the client asked for it, on the monotonic clock: what the maximum recovery time counts
         * from. It is when the request was sent, but for one that waited for a session's channel, when
         * it began to wait.
         */
        final long askedMono;
        /** When it was sent, by the producer's clock. */
        final long requestedAt;

        final Set<Integer> awaited;
        final Set<Integer> seen = new HashSet<>();
        /**
         * When the API accepted it, or a session that awaits it last took a message of it, or one
         * queued ahead of it.
         */
        long heardAt;

        EventRecovery(
                long requestId,
                long seq,
                long producerId,
                URN eventId,
                long issuedWall,
                long askedMono,
                long issuedMono,
                long requestedAt,
                Set<Integer> awaited) {
            this.requestId = requestId;
            this.seq = seq;
            this.producerId = producerId;
            this.eventId = eventId;
            this.issuedWall = issuedWall;
            this.askedMono = askedMono;
            this.requestedAt = requestedAt;
            this.awaited = awaited;
            this.heardAt = issuedMono;
        }
    }

    /** A recovery or an event recovery that ended without completing: its id, and its place in the order sent. */
    private record Ended(long requestId, long seq) {}

    /** An event recovery waiting for a session's reset to be done. */
    private record DeferredEvent(
            long producerId, URN eventId, boolean stateful, CompletableFuture<@Nullable Long> reply, long deferredAt) {}

    /** A reset the safety net waits for the API to make; once made, the session's {@code underway}. */
    private static final class PendingReset {
        /** The producers whose recoveries the API has not accepted yet. */
        final Set<Long> unaccepted;
        /** The recovery it asked for of each enabled producer of the session, by producer, as a request id. */
        final Map<Long, Long> asked = new LinkedHashMap<>();
        /** The producers whose recovery ended with its acceptance, with no session to wait for. */
        final Set<Long> endedOnAcceptance = new HashSet<>();
        /** The producer whose messages were too old. */
        final long producerId;

        final long age;
        final long number;

        PendingReset(Set<Long> unaccepted, long producerId, long age, long number) {
            this.unaccepted = unaccepted;
            this.producerId = producerId;
            this.age = age;
            this.number = number;
        }
    }
}
