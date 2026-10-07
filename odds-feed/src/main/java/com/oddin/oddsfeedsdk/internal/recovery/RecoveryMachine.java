package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.producer.Recovery;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * stay older than the limit for the window, the net asks for a recovery of every producer of the
 * session it can ask for now, and only once the API has accepted them all has the session's channel
 * replaced; it waits while one of them has a recovery in flight. Once
 * the transport reports that done, the session has lost its queue like a lost channel, and those
 * recoveries, part of whose messages may have gone with the old queue, are asked for again. It is
 * off for a producer whose last alive is older than two alive intervals, and paused for a producer
 * with a recovery in flight or a gap open. It resets each session at most {@link
 * RecoverySettings#resets()} times per cool-down, with backoff between resets; when spent the
 * session is lagging, and no producer goes down for it.
 *
 * <p>Not safe for concurrent use: the recovery actor's thread is the only one that calls it.
 */
final class RecoveryMachine {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryMachine.class);

    /** A sample's age when the producer's clock offset is not known. */
    private static final long UNKNOWN_AGE = Long.MIN_VALUE;

    /** No resume point: no session receives the producer, or none was published yet. */
    private static final long NO_POINT = -1;

    /**
     * How many of a producer's recoveries that ended without completing are remembered: each sends
     * a snapshot of its own at most, so the traffic that puts off a deadline stays bounded.
     */
    private static final int ENDED_REMEMBERED = 8;

    private final Producers producers;
    private final RecoverySettings settings;
    private final Outbox outbox;
    private final RecoveryEvents events;
    private final InstantSource clock;
    private final RecoveryCounters counters;
    /** Every event recovery's status, which any thread reads; written here only. */
    private final EventRecoveryStatuses statuses;

    private final RequestIds ids;

    /** Every producer of the producer list, in its order. */
    private final Map<Long, Track> tracks = new LinkedHashMap<>();

    private final Map<Integer, SessionState> sessions = new LinkedHashMap<>();
    private final Map<Long, EventRecovery> eventRecoveries = new HashMap<>();
    /** The callers waiting for whether the API took an event recovery, by request id. */
    private final Map<Long, CompletableFuture<@Nullable Long>> replies = new HashMap<>();
    /** Event recoveries asked for while a session that would receive them was being reset. */
    private final List<DeferredEvent> deferred = new ArrayList<>();

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
    /** The number of the last reset the safety net set out to make. */
    private long resetSeq;

    RecoveryMachine(
            Producers producers,
            RecoverySettings settings,
            Outbox outbox,
            RecoveryEvents events,
            InstantSource clock,
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
        long now = now();
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
                markDown(track, StatusCause.SESSION_OPENED, now);
                maybeRequest(track, now);
            }
        }
    }

    /**
     * A session closed: it leaves the checkpoints and the completions at once, so a recovery that
     * waited only for it completes.
     */
    void sessionClosed(int id) {
        SessionState session = sessions.remove(id);
        if (session == null) {
            return;
        }
        long now = now();
        for (Long producerId : session.lanes.keySet()) {
            Track track = track(producerId);
            Active active = track.active;
            if (active != null && active.awaited.remove(id)) {
                boolean seenAny = !active.seen.isEmpty();
                active.seen.remove(id);
                // a snapshot complete stands for the API's acceptance; with none seen, and none left
                // to wait for, the API's answer decides
                if (active.seen.containsAll(active.awaited) && (active.accepted || seenAny)) {
                    complete(track, active, now);
                }
            } else if (active == null) {
                upIfNothingMissing(track, now);
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
        startedAt = now();
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
            ended(recovery, EventRecoveryStatus.State.FAILED, "the feed closed");
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
        long now = now();
        for (Track track : tracks.values()) {
            if (track.active != null) {
                giveUp(track, now, "its snapshot went with the connection");
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
                markDown(track, StatusCause.CONNECTION_LOST, now);
            }
        }
    }

    /**
     * The connection is up with every channel open, the first time or back after a loss: recoveries
     * go out now to producers whose last alive is recent, else at their next alives.
     */
    void connectionUp() {
        connected = true;
        long now = now();
        for (Track track : tracks.values()) {
            maybeRequest(track, now);
            upIfNothingMissing(track, now);
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
        queueLost(session, StatusCause.CHANNEL_LOST, now());
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
        long now = now();
        for (Long producerId : session.lanes.keySet()) {
            maybeRequest(track(producerId), now);
        }
        releaseDeferred();
    }

    /**
     * The safety net's reset is made: the session's queue lost what it held, as with a lost
     * channel. The recoveries it waited for were asked for before the reset, so part of what they
     * sent went with the old queue: they are given up and asked for again.
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
        long now = now();
        if (replaced) {
            // only a reset made counts against the cap and the backoff
            session.resets.addLast(now);
            session.nextResetAt = now + backoff(settings.firstResetBackoff(), session.resets.size());
            counters.resets.incrementAndGet();
            events.safetyNetReset(id, session.underwayProducer, session.underwayAge);
            if (session.lagging) {
                session.lagging = false;
                events.lagging(id, false);
            }
            queueLost(session, StatusCause.SAFETY_NET_RESET, now);
        } else {
            // nothing was dropped, but the snapshot completes ignored meanwhile are gone; the net waits
            // as long as after the last reset made, and the backoff does not grow for one not made
            LOG.warn("The safety net's reset of session {} was not made", id);
            session.nextResetAt = now + backoff(settings.firstResetBackoff(), Math.max(1, session.resets.size()));
            notSeen(session, now);
        }
        releaseDeferred();
    }

    /** The event recoveries that waited for a session's channel are asked for once none waits. */
    private void releaseDeferred() {
        for (DeferredEvent event : new ArrayList<>(deferred)) {
            Track track = track(event.producerId());
            if (!awaitsChannel(track)) {
                deferred.remove(event);
                recoverEvent(event.producerId(), event.eventId(), event.stateful(), event.reply());
            }
        }
    }

    /**
     * The session's queue lost what it held: it misses everything after its checkpoints, and a
     * recovery or event recovery that waited for its snapshot complete will not see it.
     */
    private void queueLost(SessionState session, StatusCause cause, long now) {
        for (Map.Entry<Long, Lane> entry : session.lanes.entrySet()) {
            Lane lane = entry.getValue();
            lane.gap = openGap(lane.gap, lane.checkpoint, false);
            lane.above = false;
        }
        for (Long producerId : session.lanes.keySet()) {
            markDown(track(producerId), cause, now);
        }
        notSeen(session, now);
    }

    /**
     * The session will not see the snapshot completes it has not seen yet: the recoveries and event
     * recoveries waiting for them are given up, and asked for again as far as anything is missing.
     */
    private void notSeen(SessionState session, long now) {
        int id = session.info.id();
        giveUpEvents(id);
        for (Long producerId : session.lanes.keySet()) {
            Track track = track(producerId);
            Active active = track.active;
            if (active != null && active.awaited.contains(id) && !active.seen.contains(id)) {
                giveUp(track, now, "its snapshot went with session " + id + "'s queue");
            }
            maybeRequest(track, now);
        }
    }

    // ---- the feed

    /**
     * An alive from the SDK's own alive channel: the producer is there. It measures the producer's
     * clock offset and alive interval; subscribed, it is the point everything before was sent;
     * unsubscribed, the producer has stopped sending since the last one that was.
     *
     * @param receivedAt when the SDK received it, epoch millis by its clock
     */
    void alive(long producerId, long generatedAt, long receivedAt, boolean subscribed) {
        Track track = known(producerId);
        if (track == null || !enabled(track)) {
            return;
        }
        long now = now();
        // the alive after the one that gave a recovery up: it may be asked for again
        track.awaitsAlive = false;
        if (track.lastAliveAt != 0) {
            // a second at least, and the maximum inactivity at most, unless that is less
            long measured = Math.clamp(
                    receivedAt - track.lastAliveAt,
                    1_000,
                    Math.max(1_000, settings.maxInactivity().toMillis()));
            track.aliveInterval += (measured - track.aliveInterval) / 4;
        }
        track.lastAliveAt = receivedAt;
        track.lastAliveGen = generatedAt;
        track.offset = receivedAt - generatedAt;
        producers.setLastMessageTimestamp(producerId, Math.max(1, receivedAt));
        if (track.inactive) {
            // an alive after a gap re-arms the cap at once
            track.inactive = false;
            rearm(track);
        }
        if (subscribed) {
            track.safePoint = Math.max(track.safePoint, generatedAt);
        } else {
            openProducerGap(track, generatedAt);
            Active active = track.active;
            if (active != null && active.accepted && receivedAt >= active.acceptedAt) {
                // still unsubscribed after the producer took the request: it lost it, restarting say,
                // and will send neither the snapshot nor its snapshot complete
                giveUp(track, now, "the producer says unsubscribed since it accepted it");
                track.awaitsAlive = true;
            }
            markDown(track, StatusCause.UNSUBSCRIBED, now);
        }
        maybeRequest(track, now);
    }

    /**
     * A session has finished a message of the producer. A live one moves the session's checkpoint
     * and is the safety net's sample; a snapshot one, from a recovery, does neither.
     *
     * @param takenAt when the session's dispatcher took it, epoch millis by the SDK's clock
     * @param requestId the recovery's request id a snapshot message carries, 0 for a live one
     */
    void processed(int id, long producerId, long generatedAt, long takenAt, long requestId) {
        Track track = known(producerId);
        SessionState session = sessions.get(id);
        Lane lane = session == null || track == null ? null : session.lanes.get(producerId);
        if (session == null || track == null || lane == null) {
            return;
        }
        producers.setLastMessageTimestamp(producerId, Math.max(1, takenAt));
        producers.setLastProcessedMessageGenTimestamp(producerId, generatedAt);
        lane.lastAt = takenAt;
        heard(track, id, generatedAt, requestId);
        if (requestId == 0) {
            lane.checkpoint = Math.max(lane.checkpoint, generatedAt);
            sample(session, lane, track, generatedAt, takenAt);
        }
    }

    /**
     * A session has finished an alive from its own queue: everything the producer sent before it is
     * done, so a subscribed one moves the session's checkpoint. Both kinds are samples.
     */
    void sessionAlive(int id, long producerId, long generatedAt, long takenAt, boolean subscribed) {
        Track track = known(producerId);
        SessionState session = sessions.get(id);
        Lane lane = session == null || track == null ? null : session.lanes.get(producerId);
        if (session == null || track == null || lane == null) {
            return;
        }
        producers.setLastProcessedMessageGenTimestamp(producerId, generatedAt);
        lane.lastAt = takenAt;
        heard(track, id, generatedAt, 0);
        if (subscribed) {
            lane.checkpoint = Math.max(lane.checkpoint, generatedAt);
        }
        sample(session, lane, track, generatedAt, takenAt);
    }

    /**
     * Whether what a session took says the recovery in flight is still on its way to it: a message
     * of its snapshot, or one the snapshot and its snapshot complete queue behind - a live message or
     * an alive sent before the request, or a snapshot message of an earlier recovery of the producer
     * that failed or was given up, or of an event recovery asked for before it. Any of them puts off
     * the deadline for its snapshot complete. An event recovery asked for since does not: one after
     * another, they would hold a recovery whose snapshot complete is lost for the maximum recovery
     * time.
     */
    private void heard(Track track, int id, long generatedAt, long requestId) {
        Active active = track.active;
        if (active != null
                && active.awaited.contains(id)
                && !active.seen.contains(id)
                && (requestId == 0 ? generatedAt < active.requestedAt : ahead(track, active, requestId))) {
            active.heardAt = now();
        }
    }

    /** Whether a snapshot message of {@code requestId} is the recovery's own, or queued ahead of it. */
    private boolean ahead(Track track, Active active, long requestId) {
        if (requestId == active.requestId || track.ended.contains(requestId)) {
            return true;
        }
        EventRecovery event = eventRecoveries.get(requestId);
        return event != null && event.producerId == track.id && event.issuedAt < active.issuedAt;
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
                if (session != null && pending != null && pending.producers.contains(producerId)) {
                    // the session has what the recovery sent: a reset now would drop it
                    cancelReset(session, now());
                }
                if (!active.accepted) {
                    // a snapshot complete says the API took the request, whatever it answers later
                    accepted(track, active, now());
                }
                if (track.active == active && active.seen.containsAll(active.awaited)) {
                    complete(track, active, now());
                }
            }
            return;
        }
        EventRecovery event = eventRecoveries.get(requestId);
        if (event != null && event.producerId == producerId) {
            if (event.awaited.contains(id)) {
                event.seen.add(id);
                if (event.seen.containsAll(event.awaited)) {
                    // its status ends before a caller still waiting hears the API took it
                    eventCompleted(event);
                } else {
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
        long now = now();
        CompletableFuture<@Nullable Long> reply = replies.remove(requestId);
        if (reply != null) {
            EventRecovery event = eventRecoveries.get(requestId);
            if (failure == null) {
                if (event != null && event.awaited.isEmpty()) {
                    // no session takes snapshot completes: nothing would ever complete it. Its
                    // status ends first: a caller that reads it once the future completes sees it ended
                    eventCompleted(event);
                }
                outbox.reply(reply, requestId);
            } else {
                EventRecovery refused = eventRecoveries.remove(requestId);
                if (refused != null) {
                    ended(
                            refused,
                            EventRecoveryStatus.State.FAILED,
                            "the API did not accept it: " + failure.getMessage());
                }
                counters.eventRefused.incrementAndGet();
                LOG.warn("Event recovery request {} failed: {}", requestId, failure.getMessage());
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
                    accepted(track, active, now);
                } else {
                    failed(track, active, now, "the API did not accept it: " + failure.getMessage());
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
        if (reply.isDone()) {
            // the caller stopped waiting: a request sent now would carry an id it never got
            counters.eventCallerGone.incrementAndGet();
            LOG.info("Event recovery of {} not asked for: its caller stopped waiting", eventId);
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
            LOG.warn("Event recovery of {} refused: the connection is not up", eventId);
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
            LOG.warn("Event recovery of {} refused: {} in flight for producer {}", eventId, inFlight, producerId);
            outbox.reply(reply, null);
            return;
        }
        if (awaitsChannel(track)) {
            // a session that receives it is being reset, or has lost its channel: its snapshot could
            // go to either channel, or to none
            LOG.info("Event recovery of {} waits for a session's channel", eventId);
            deferred.add(new DeferredEvent(producerId, eventId, stateful, reply, now()));
            return;
        }
        long requestId = ids.next(this::inFlight);
        var recovery = new EventRecovery(requestId, producerId, eventId, now(), awaited(track));
        eventRecoveries.put(requestId, recovery);
        statuses.pending(requestId, producerId, eventId, Instant.ofEpochMilli(recovery.issuedAt));
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
        long now = now();
        for (Track track : tracks.values()) {
            if (!enabled(track)) {
                continue;
            }
            Active active = track.active;
            if (active != null) {
                if (now - active.issuedAt > settings.maxRecoveryTime().toMillis()) {
                    counters.timedOut.incrementAndGet();
                    failed(track, active, now, "no snapshot complete within " + settings.maxRecoveryTime());
                } else if (now - active.heardAt
                                > settings.snapshotCompleteTimeout().toMillis()
                        && !underway(track)) {
                    // a reset underway gives it up once done; else its snapshot complete is lost
                    counters.timedOut.incrementAndGet();
                    failed(
                            track,
                            active,
                            now,
                            "no snapshot complete, nor anything on its way, within "
                                    + settings.snapshotCompleteTimeout());
                }
            }
            if (started) {
                long last = track.lastAliveAt != 0 ? track.lastAliveAt : startedAt;
                if (!track.inactive && now - last > settings.maxInactivity().toMillis()) {
                    track.inactive = true;
                    openProducerGap(track, track.lastAliveGen);
                    // a producer that went silent while a recovery was in flight may have lost it
                    giveUp(track, now, "the producer sent no alive for " + settings.maxInactivity());
                    markDown(track, StatusCause.ALIVE_INTERVAL_VIOLATION, now);
                }
                checkDelay(track, now);
            }
            maybeRequest(track, now);
        }
        for (EventRecovery recovery : new ArrayList<>(eventRecoveries.values())) {
            if (now - recovery.issuedAt > settings.maxRecoveryTime().toMillis()) {
                eventRecoveries.remove(recovery.requestId);
                ended(
                        recovery,
                        EventRecoveryStatus.State.TIMED_OUT,
                        "no snapshot complete within " + settings.maxRecoveryTime());
                counters.eventExpired.incrementAndGet();
                CompletableFuture<@Nullable Long> reply = replies.remove(recovery.requestId);
                if (reply != null) {
                    outbox.reply(reply, null);
                }
                LOG.warn(
                        "Event recovery {} of {} got no snapshot complete within {}",
                        recovery.requestId,
                        recovery.eventId,
                        settings.maxRecoveryTime());
            }
        }
        for (DeferredEvent event : new ArrayList<>(deferred)) {
            if (now - event.deferredAt() > settings.maxRecoveryTime().toMillis()) {
                // the reset it waited for never ended: it expires as one asked for would
                deferred.remove(event);
                counters.eventExpired.incrementAndGet();
                outbox.reply(event.reply(), null);
                LOG.warn(
                        "Event recovery of {} waited for a reset longer than {}",
                        event.eventId(),
                        settings.maxRecoveryTime());
            }
        }
        statuses.expire(Instant.ofEpochMilli(now));
    }

    // ---- recoveries

    /** Asks for a recovery when the producer misses something and nothing holds the request back. */
    private void maybeRequest(Track track, long now) {
        if (hasGaps(track) && canRequest(track, now)) {
            if (track.capSpentAt != 0) {
                LOG.info("Recovery of producer {} re-armed after the cool-down", track.id);
                rearm(track);
            }
            request(track, now);
        }
    }

    /**
     * Whether a recovery may go out now: none in flight, a session to receive it, the connection up,
     * every channel that receives it bound, an alive that says the producer is there - after the one
     * that gave the last recovery up - and its backoff and its cap's cool-down over.
     */
    private boolean canRequest(Track track, long now) {
        return started
                && connected
                && track.active == null
                && enabled(track)
                && receivers(track) > 0
                && aliveRecent(track, now)
                && !track.awaitsAlive
                && !awaitsChannel(track)
                && now >= track.retryAt
                && (track.capSpentAt == 0
                        || now - track.capSpentAt >= settings.cooldown().toMillis());
    }

    private void request(Track track, long now) {
        long after = after(track, now);
        long requestId = ids.next(this::inFlight);
        var active = new Active(requestId, after, now, now - track.offset, gapSeq, awaited(track));
        active.causes.add(track.cause);
        track.active = active;
        counters.requested.incrementAndGet();
        if (track.failures > 0) {
            counters.reissued.incrementAndGet();
        }
        LOG.info(
                "Recovery {} of producer {} {}",
                requestId,
                track.id,
                after == 0 ? "as a full snapshot" : "from " + Instant.ofEpochMilli(after));
        outbox.request(new Outbox.Call.Snapshot(
                track.id, track.producer.getName(), requestId, after == 0 ? null : Instant.ofEpochMilli(after)));
    }

    /**
     * Where a recovery starts: the oldest open gap, clamped to the producer's stateful recovery
     * window; for none, 0.0.x's initial snapshot interval when set, else 0 for a full snapshot.
     */
    private long after(Track track, long now) {
        long after = track.gap == null ? Long.MAX_VALUE : track.gap.from;
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            if (lane != null && lane.gap != null) {
                after = Math.min(after, lane.gap.from);
            }
        }
        int window = track.producer.getStatefulRecoveryWindowInMinutes();
        if (after != 0 && window > 0) {
            // the window counts back from now by the producer's clock, as the gaps' starts are
            after = Math.max(
                    after, now - track.offset - Duration.ofMinutes(window).toMillis());
        }
        return after;
    }

    private void accepted(Track track, Active active, long now) {
        active.accepted = true;
        active.acceptedAt = now;
        producers.setRecoveryInfo(
                track.id, new Recovery(active.after, active.issuedAt, active.requestId, settings.nodeId(), true));
        for (SessionState session : sessions.values()) {
            PendingReset pending = session.pending;
            if (pending != null && pending.unaccepted.remove(track.id) && pending.unaccepted.isEmpty()) {
                reset(session, pending, now);
            }
        }
        if (track.active == active && active.awaited.isEmpty()) {
            // no session takes snapshot completes: nothing would ever complete it
            complete(track, active, now);
        }
    }

    /**
     * Every session has seen the snapshot complete: the gaps it covers are closed. With gaps left
     * that opened since, one more recovery; with none, the producer is up.
     *
     * <p>A pending reset of another session is left as it is: a session's reset is cancelled only by
     * its own snapshot complete, the one evidence that its backlog was replaced. A low-priority
     * session next to a high-priority one sees none, so its reset goes ahead, and once it is done
     * what the recovery had sent into the old queue is asked for again.
     */
    private void complete(Track track, Active active, long now) {
        track.active = null;
        track.pausedUntil = now;
        if (!active.accepted) {
            // the snapshot complete was quicker than the API's answer
            producers.setRecoveryInfo(
                    track.id, new Recovery(active.after, active.issuedAt, active.requestId, settings.nodeId(), true));
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
                now - active.issuedAt,
                active.causes);
        if (hasDownGaps(track)) {
            maybeRequest(track, now);
            return;
        }
        bringUp(track, StatusCause.RECOVERY_COMPLETED, now);
    }

    /**
     * The API did not accept the recovery, or it timed out: asked for again after the backoff, or,
     * with the cap spent, not before the cool-down.
     */
    private void failed(Track track, Active active, long now, String reason) {
        ended(track, active, now);
        producers.setRecoveryInfo(
                track.id, new Recovery(active.after, active.issuedAt, active.requestId, settings.nodeId(), false));
        counters.failed.incrementAndGet();
        for (SessionState session : sessions.values()) {
            PendingReset pending = session.pending;
            if (pending != null && pending.unaccepted.contains(track.id)) {
                cancelReset(session, now);
                counters.resetRequestsFailed.incrementAndGet();
                LOG.warn(
                        "The safety net did not reset session {}: recovery {} of producer {} failed, {}",
                        session.info.id(),
                        active.requestId,
                        track.id,
                        reason);
                events.safetyNetRequestFailed(session.info.id(), track.id, reason);
            }
        }
        if (!hasGaps(track)) {
            // nothing is missing, so nothing is asked for again, and nothing failed in a row
            track.failures = 0;
            upIfNothingMissing(track, now);
            return;
        }
        track.failures++;
        if (track.failures > settings.reissues()) {
            track.capSpentAt = now;
            LOG.warn(
                    "Recovery {} of producer {} failed, {}; {} in a row, so the producer stays down for {}",
                    active.requestId,
                    track.id,
                    reason,
                    track.failures,
                    settings.cooldown());
            markDown(track, StatusCause.RECOVERY_FAILED, now);
        } else {
            track.retryAt = now + backoff(settings.firstReissueBackoff(), track.failures);
            LOG.warn(
                    "Recovery {} of producer {} failed, {}; asked for again in {} ms",
                    active.requestId,
                    track.id,
                    reason,
                    track.retryAt - now);
        }
    }

    /**
     * The recovery's snapshot will not come, through no fault of the request: lost with a queue, or
     * by the producer. It is no longer waited for, its gaps stay open, and no failure is counted.
     */
    private void giveUp(Track track, long now, String reason) {
        Active active = track.active;
        if (active == null) {
            return;
        }
        ended(track, active, now);
        counters.abandoned.incrementAndGet();
        LOG.info("Recovery {} of producer {} given up: {}", active.requestId, track.id, reason);
        for (SessionState session : sessions.values()) {
            PendingReset pending = session.pending;
            if (pending != null && pending.unaccepted.contains(track.id)) {
                cancelReset(session, now);
            }
        }
    }

    /**
     * The recovery is no longer waited for, without completing: what it sent may still be on its
     * way, ahead of the snapshot of the next one asked for.
     */
    private void ended(Track track, Active active, long now) {
        track.active = null;
        track.pausedUntil = now;
        track.ended.addFirst(active.requestId);
        if (track.ended.size() > ENDED_REMEMBERED) {
            track.ended.removeLast();
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
        statuses.ended(recovery.requestId, state, Instant.ofEpochMilli(now()), reason);
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
            from = now() - initial.toMillis();
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
     * opened after its last session closed still takes it back.
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

    private void sample(SessionState session, Lane lane, Track track, long generatedAt, long takenAt) {
        long now = now();
        if (!offsetFresh(track, now)) {
            lane.age = UNKNOWN_AGE;
            lane.above = false;
            return;
        }
        long age = takenAt - generatedAt - track.offset;
        lane.age = age;
        if (track.active != null
                || hasGaps(track)
                || session.pending != null
                || session.underway != 0
                || takenAt <= track.pausedUntil) {
            // a recovery's snapshot ahead of a live message makes it old, on every session
            lane.above = false;
            return;
        }
        if (age <= settings.staleLimit().toMillis()) {
            lane.above = false;
            if (session.lagging && session.lanes.values().stream().noneMatch(other -> other.above)) {
                session.lagging = false;
                LOG.info("Session {} caught up", session.info.id());
                events.lagging(session.info.id(), false);
            }
            return;
        }
        if (!lane.above) {
            lane.above = true;
            lane.aboveSince = takenAt;
        } else if (takenAt - lane.aboveSince >= settings.staleWindow().toMillis()) {
            trigger(session, track, age, now);
        }
    }

    /**
     * The session has been too far behind for the window: a recovery for each of its producers
     * first, the reset once the API has accepted them all. Spent, the session is lagging instead.
     */
    private void trigger(SessionState session, Track track, long age, long now) {
        while (!session.resets.isEmpty()
                && now - session.resets.getFirst() >= settings.cooldown().toMillis()) {
            session.resets.removeFirst();
        }
        if (session.resets.size() >= settings.resets()) {
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
        if (now < session.nextResetAt) {
            return;
        }
        if (session.underway != 0 || !canRequest(track, now)) {
            return;
        }
        // wait for a recovery in flight; a producer that cannot be asked now - silent, its cap
        // spent, in backoff - sends the session nothing to drop, and once the reset is done it
        // misses what the queue held like any other, to be asked for when it can be
        for (Long producerId : session.lanes.keySet()) {
            Track other = track(producerId);
            if (enabled(other) && other.active != null) {
                return;
            }
        }
        var unaccepted = new HashSet<Long>();
        for (Map.Entry<Long, Lane> entry : session.lanes.entrySet()) {
            Track other = track(entry.getKey());
            if (enabled(other) && canRequest(other, now)) {
                Lane lane = entry.getValue();
                lane.gap = openGap(lane.gap, lane.checkpoint, true);
                unaccepted.add(other.id);
            }
        }
        session.pending = new PendingReset(unaccepted, track.id, age, ++resetSeq);
        LOG.warn(
                "Session {} is {} ms behind producer {}: the safety net asks for recoveries before it resets",
                session.info.id(),
                age,
                track.id);
        for (Long producerId : unaccepted) {
            maybeRequest(track(producerId), now);
        }
    }

    /**
     * Every recovery the reset waited for is accepted: the session's channel is replaced. The
     * reset stays pending until the transport reports it done, and meanwhile the session's snapshot
     * completes are ignored: they come from the queue being replaced. Its producers' recoveries are
     * in flight all the while, so nothing more is asked for them.
     */
    private void reset(SessionState session, PendingReset pending, long now) {
        session.pending = null;
        session.underway = pending.number;
        session.underwayProducer = pending.producerId;
        session.underwayAge = pending.age;
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
        outbox.reset(session.info.id(), pending.number);
        for (Long producerId : session.lanes.keySet()) {
            markDown(track(producerId), StatusCause.SAFETY_NET_RESET, now);
        }
    }

    /** A reset that will not be made: its gaps close, and the net waits before it tries again. */
    private void cancelReset(SessionState session, long now) {
        session.pending = null;
        session.nextResetAt = now + backoff(settings.firstResetBackoff(), session.resets.size() + 1);
        for (Lane lane : session.lanes.values()) {
            Gap gap = lane.gap;
            if (gap != null && gap.pendingReset) {
                lane.gap = null;
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
    private void checkDelay(Track track, long now) {
        long max = settings.maxInactivity().toMillis();
        boolean delayed = false;
        for (SessionState session : sessions.values()) {
            Lane lane = session.lanes.get(track.id);
            if (lane != null
                    && ((lane.age != UNKNOWN_AGE && lane.age > max && offsetFresh(track, now))
                            || (lane.lastAt != 0 && now - lane.lastAt > max && aliveRecent(track, now)))) {
                delayed = true;
            }
        }
        track.delayed = delayed;
        if (delayed && !track.down) {
            markDown(track, StatusCause.PROCESSING_DELAY, now);
        } else if (!delayed && track.down && track.cause == StatusCause.PROCESSING_DELAY) {
            markUp(track, StatusCause.DELAY_STABILIZED, now);
        }
    }

    /**
     * A producer down only for gaps that went away with a closed session, or for a lost connection
     * that no session it has now missed anything of, comes back.
     */
    private void upIfNothingMissing(Track track, long now) {
        if (track.down
                && connected
                && track.everRecovered
                && track.active == null
                && track.cause != StatusCause.PROCESSING_DELAY
                && !hasDownGaps(track)) {
            // nothing is missing: what failed before does not count against what comes next
            rearm(track);
            bringUp(track, StatusCause.RECOVERY_COMPLETED, now);
        }
    }

    /**
     * Nothing is missing any more: the producer is up, unless a session processes it late, which
     * keeps it down for that until the session catches up.
     */
    private void bringUp(Track track, StatusCause cause, long now) {
        if (track.delayed) {
            markDown(track, StatusCause.PROCESSING_DELAY, now);
        } else {
            markUp(track, cause, now);
        }
    }

    /**
     * Down for {@code cause}: reported at the cause's level when the producer was up or down for
     * another cause, and to 0.0.x's callback only when it was up or down for another public reason.
     */
    private void markDown(Track track, StatusCause cause, long now) {
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
        tell(new ProducerStatusChange(track.id, true, track.delayed, cause, now), publicChange);
    }

    /**
     * Up for {@code cause}; the first time since the feed opened, whatever brought it up, it is up
     * for its first recovery, which the client hears of once per producer, as in 0.0.x.
     */
    private void markUp(Track track, StatusCause given, long now) {
        if (!enabled(track) || !track.down) {
            return;
        }
        StatusCause cause = track.reportedUp ? given : StatusCause.FIRST_RECOVERY_COMPLETED;
        track.reportedUp = true;
        track.down = false;
        track.cause = cause;
        producers.setDown(track.id, false);
        LOG.info("Producer {} up: {}", track.id, cause.description());
        tell(new ProducerStatusChange(track.id, false, track.delayed, cause, now), true);
    }

    /** A status change: the cause-level event always, the public one when the flag or reason changed. */
    private void tell(ProducerStatusChange change, boolean publicChange) {
        if (publicChange) {
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
                ended(recovery, EventRecoveryStatus.State.FAILED, "its snapshot went with a lost queue");
                counters.eventAbandoned.incrementAndGet();
                CompletableFuture<@Nullable Long> reply = replies.remove(recovery.requestId);
                if (reply != null) {
                    outbox.reply(reply, null);
                }
                LOG.info(
                        "Event recovery {} of {} given up: its snapshot went with a lost queue",
                        recovery.requestId,
                        recovery.eventId);
            }
        }
    }

    private boolean aliveRecent(Track track, long now) {
        return track.lastAliveAt != 0
                && !track.inactive
                && now - track.lastAliveAt <= settings.maxInactivity().toMillis();
    }

    /** Whether the offset is recent enough to correct ages with: an alive within two intervals. */
    private boolean offsetFresh(Track track, long now) {
        return track.lastAliveAt != 0 && now - track.lastAliveAt <= 2 * track.aliveInterval;
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

    private boolean inFlight(long requestId) {
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
                        "A fact about producer {}, which the producer list does not have, is ignored; {} so far",
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

    private long now() {
        return clock.millis();
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
        /** When the last alive arrived, by the SDK's clock; 0 before the first. */
        long lastAliveAt;

        long lastAliveGen;
        /** The last subscribed alive's timestamp: the producer has sent everything before it. */
        long safePoint;
        /** The SDK's clock minus the producer's, measured on the last alive. */
        long offset;
        /** How often the producer sends an alive, in millis, a moving average. */
        long aliveInterval;
        /** Whether the producer went too long without an alive, and no alive has come since. */
        boolean inactive;
        /** Whether an unsubscribed alive gave the last recovery up, and no alive has come since. */
        boolean awaitsAlive;
        /** What every session misses because the producer stopped sending; null for nothing. */
        @Nullable
        Gap gap;
        /** The resume point last published, or {@link #NO_POINT}. */
        long published = NO_POINT;

        @Nullable
        Active active;
        /**
         * The request ids of its last recoveries that failed or were given up, newest first, at most
         * {@code ENDED_REMEMBERED}: what they sent may still be ahead of the one in flight.
         */
        final ArrayDeque<Long> ended = new ArrayDeque<>();
        /** Samples taken up to this time are not the safety net's: a recovery was in flight. */
        long pausedUntil;
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
        /** When the session last took a message or an alive of the producer, 0 before the first. */
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
        /** Epoch millis, 0 for a full snapshot. */
        final long after;

        final long issuedAt;
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
        /** When the API accepted it, or a snapshot complete said so, by the SDK's clock. */
        long acceptedAt;
        /**
         * When a session that awaits it last took something that says it is on its way, or when it
         * was asked for: its snapshot complete is waited for this long after, at most.
         */
        long heardAt;

        Active(long requestId, long after, long issuedAt, long requestedAt, long covers, Set<Integer> awaited) {
            this.requestId = requestId;
            this.after = after;
            this.issuedAt = issuedAt;
            this.requestedAt = requestedAt;
            this.covers = covers;
            this.awaited = awaited;
            this.heardAt = issuedAt;
        }
    }

    /** An event recovery in flight. */
    private static final class EventRecovery {
        final long requestId;
        final long producerId;
        final URN eventId;
        final long issuedAt;
        final Set<Integer> awaited;
        final Set<Integer> seen = new HashSet<>();

        EventRecovery(long requestId, long producerId, URN eventId, long issuedAt, Set<Integer> awaited) {
            this.requestId = requestId;
            this.producerId = producerId;
            this.eventId = eventId;
            this.issuedAt = issuedAt;
            this.awaited = awaited;
        }
    }

    /** An event recovery waiting for a session's reset to be done. */
    private record DeferredEvent(
            long producerId, URN eventId, boolean stateful, CompletableFuture<@Nullable Long> reply, long deferredAt) {}

    /** A reset the safety net waits for the API to make; once made, the session's {@code underway}. */
    private static final class PendingReset {
        /** The producers whose recoveries the API has not accepted yet. */
        final Set<Long> unaccepted;
        /** The producers it asked for recoveries of. */
        final Set<Long> producers;
        /** The producer whose messages were too old. */
        final long producerId;

        final long age;
        final long number;

        PendingReset(Set<Long> unaccepted, long producerId, long age, long number) {
            this.unaccepted = unaccepted;
            this.producers = Set.copyOf(unaccepted);
            this.producerId = producerId;
            this.age = age;
            this.number = number;
        }
    }
}
