package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The feed's health as {@code OddsFeed.getHealth()} found it: a state for the feed, one for each
 * part it watches, and the counters of everything the SDK deliberately gives up - a delivery it
 * dropped, a catalog it served stale, a message it could not read - grouped by the part that counts
 * them. Each counter counts from the feed's start and only grows; {@link #counters()} has every
 * value under a stable name, for a metrics exporter. New in 1.0.
 *
 * <p>A part is {@link HealthState#DEGRADED} while it works with a quality the SDK gave up: a session
 * lagging, with the safety net's resets spent, or a catalog that has served a value stale, its
 * refreshes failing, for an hour or more. It is {@link HealthState#STALLED} when the SDK's own watch finds it not
 * moving: in one callback for more than 30 seconds, with a queue that has not moved for 30 seconds,
 * or held by a deadlock; it is healthy again once that ends. The feed's state is the worst of its
 * parts'. Each change is also told to {@code GlobalEventsListener.onHealthEvent}.
 *
 * @param state the worst of the parts' states; healthy when there is none yet
 * @param components the state of each part the feed has now: the events, the catalogs, the timers
 *     and the JVM's threads once the feed has started, the consumer and the sessions once it is open,
 *     and the alives and the recovery once a feed that is not a replay feed is open
 * @param transport the broker connection; not connected, and nothing counted, before the feed opens
 * @param alives the SDK's own alive consumer; nothing counted on a replay feed or before it opens
 * @param sessions each session, in the order they were built; none before the feed opens
 * @param recovery the producers' recoveries and the safety net; nothing counted on a replay feed or
 *     before the feed opens
 * @param catalogs the market descriptions, the market variants, the void reasons and the match
 *     statuses, once the feed has started; none before
 * @param caches the entity caches and their background loads; nothing counted before the feed starts
 * @param events the thread that runs the feed's own callbacks
 * @param at when it was found, by the SDK's clock
 */
public record FeedHealth(
        HealthState state,
        Map<HealthComponent, HealthState> components,
        Transport transport,
        Alives alives,
        List<Session> sessions,
        Recovery recovery,
        List<Catalog> catalogs,
        Caches caches,
        Events events,
        Instant at) {

    public FeedHealth {
        var copied = new EnumMap<HealthComponent, HealthState>(HealthComponent.class);
        copied.putAll(components);
        components = Collections.unmodifiableMap(copied);
        sessions = List.copyOf(sessions);
        catalogs = List.copyOf(catalogs);
    }

    /**
     * Every value of this health under its own name, in a fixed order: counters as counted, a flag
     * as 1 or 0, a duration in milliseconds, and a state as 0 for healthy, 1 for degraded and 2 for
     * stalled. A session's names start {@code session.<id>.}, a catalog's {@code catalog.<name>.},
     * such as {@code session.1.queue_depth} and {@code catalog.market_descriptions.stale_for_millis}.
     */
    public Map<String, Long> counters() {
        var named = new LinkedHashMap<String, Long>();
        named.put("state", level(state));
        components.forEach((component, health) ->
                named.put("component." + component.name().toLowerCase(Locale.ROOT) + ".state", level(health)));
        named.put("transport.connected", flag(transport.connected()));
        named.put("transport.reconnects", transport.reconnects());
        named.put("alives.queued", (long) alives.queued());
        named.put("alives.handled", alives.handled());
        named.put("alives.dropped", alives.dropped());
        named.put("alives.unreadable", alives.unreadable());
        for (Session session : sessions) {
            String prefix = "session." + session.id() + ".";
            named.put(prefix + "state", level(session.state()));
            named.put(prefix + "lagging", flag(session.lagging()));
            named.put(prefix + "queue_depth", (long) session.queueDepth());
            named.put(prefix + "queue_overflows", session.queueOverflows());
            named.put(prefix + "epoch_discards", session.epochDiscards());
            named.put(prefix + "skipped_acks", session.skippedAcks());
            named.put(prefix + "handled", session.handled());
            named.put(prefix + "unparsable", session.unparsable());
            named.put(prefix + "oversized", session.oversized());
            named.put(prefix + "pipeline_failures", session.pipelineFailures());
            named.put(prefix + "callback_failures", session.callbackFailures());
            named.put(prefix + "unknown_producers", session.unknownProducers());
            named.put(prefix + "repeated_fixture_changes", session.repeatedFixtureChanges());
        }
        named.put("recovery.requested", recovery.requested());
        named.put("recovery.reissued", recovery.reissued());
        named.put("recovery.failed", recovery.failed());
        named.put("recovery.timed_out", recovery.timedOut());
        named.put("recovery.abandoned", recovery.abandoned());
        named.put("recovery.completed", recovery.completed());
        named.put("recovery.unknown_completions", recovery.unknownCompletions());
        named.put("recovery.unknown_producers", recovery.unknownProducers());
        named.put("recovery.event_requested", recovery.eventRequested());
        named.put("recovery.event_refused", recovery.eventRefused());
        named.put("recovery.event_expired", recovery.eventExpired());
        named.put("recovery.event_abandoned", recovery.eventAbandoned());
        named.put("recovery.event_caller_gone", recovery.eventCallerGone());
        named.put("recovery.event_statuses_dropped", recovery.eventStatusesDropped());
        named.put("recovery.safety_net_resets", recovery.safetyNetResets());
        named.put("recovery.safety_net_dropped", recovery.safetyNetDropped());
        named.put("recovery.safety_net_requests_failed", recovery.safetyNetRequestsFailed());
        named.put("recovery.facts_dropped", recovery.factsDropped());
        named.put("recovery.facts_failed", recovery.factsFailed());
        for (Catalog catalog : catalogs) {
            String prefix = "catalog." + catalog.name().replace(' ', '_') + ".";
            named.put(prefix + "state", level(catalog.state()));
            named.put(prefix + "served_stale", catalog.servedStale());
            named.put(prefix + "stale_for_millis", catalog.staleFor().toMillis());
            named.put(prefix + "failed_fetches", catalog.failedFetches());
            named.put(prefix + "failing", catalog.failing());
            named.put(prefix + "evicted_for_room", catalog.evictedForRoom());
        }
        named.put("caches.side_loads_dropped", caches.sideLoadsDropped());
        named.put("caches.side_loads_failed", caches.sideLoadsFailed());
        named.put("caches.stale_fetches_discarded", caches.staleFetchesDiscarded());
        named.put("caches.invalidations_forgotten", caches.invalidationsForgotten());
        named.put("caches.live_states_dropped", caches.liveStatesDropped());
        named.put("caches.dedup_evictions", caches.dedupEvictions());
        named.put("events.control_dropped", events.controlDropped());
        named.put("events.telemetry_dropped", events.telemetryDropped());
        named.put("events.raw_data_dropped", events.rawDataDropped());
        named.put("events.callback_failures", events.callbackFailures());
        return Collections.unmodifiableMap(named);
    }

    private static long level(HealthState state) {
        return switch (state) {
            case HEALTHY -> 0;
            case DEGRADED -> 1;
            case STALLED -> 2;
        };
    }

    private static long flag(boolean set) {
        return set ? 1 : 0;
    }

    /**
     * The broker connection.
     *
     * @param connected whether the feed has a connection to the broker now
     * @param reconnects connections made again after a loss; the first connect is none
     */
    public record Transport(boolean connected, long reconnects) {}

    /**
     * The SDK's own alive consumer, which tells the recovery how each producer is.
     *
     * @param queued alives waiting to be read now
     * @param handled alives read
     * @param dropped alives dropped for want of room in the queue; the next one of the producer
     *     stands in for it
     * @param unreadable deliveries on the alive channel that were no alive the SDK could read
     */
    public record Alives(int queued, long handled, long dropped, long unreadable) {}

    /**
     * One session.
     *
     * @param id the feed's number for the session, from 1 in the order they were built; the key of its
     *     counters
     * @param state degraded while it lags; stalled when the SDK's own watch finds it not moving
     * @param lagging whether it fell behind with the safety net's resets spent, as {@code
     *     onSessionLagChange} last told
     * @param queueDepth deliveries waiting for the session's thread now
     * @param queueOverflows deliveries refused for want of room, which the prefetch should never let
     *     happen
     * @param epochDiscards deliveries of a channel replaced, by a reconnect or the safety net, that
     *     the session never got; recovery covers them
     * @param skippedAcks acknowledgements skipped because their delivery's channel was gone
     * @param handled messages handled to their acknowledgement
     * @param unparsable messages that did not decode, the oversized ones included
     * @param oversized messages over the maximum message size, which were not decoded
     * @param pipelineFailures steps of the SDK's own that failed on a message: a cache write, a
     *     build, a decode
     * @param callbackFailures the session listener's callbacks that threw
     * @param unknownProducers messages dropped for a producer the producer list does not have
     * @param repeatedFixtureChanges fixture changes dropped as delivered already
     */
    public record Session(
            int id,
            OddsFeedSession session,
            HealthState state,
            boolean lagging,
            int queueDepth,
            long queueOverflows,
            long epochDiscards,
            long skippedAcks,
            long handled,
            long unparsable,
            long oversized,
            long pipelineFailures,
            long callbackFailures,
            long unknownProducers,
            long repeatedFixtureChanges) {}

    /**
     * The producers' recoveries, the event recoveries and the safety net.
     *
     * @param requested producer recoveries asked for, re-issues included
     * @param reissued producer recoveries asked for again after one failed or timed out
     * @param failed producer recoveries the API did not accept, or that timed out
     * @param timedOut producer recoveries with no snapshot complete within the maximum recovery time
     * @param abandoned producer recoveries given up because what they would send was lost with a
     *     queue
     * @param completed producer recoveries completed
     * @param unknownCompletions snapshot completes for no recovery in flight: another instance's, or
     *     one given up
     * @param unknownProducers reports about a producer the producer list does not have
     * @param eventRequested event recoveries asked for
     * @param eventRefused event recoveries the API did not accept, or too many in flight turned away
     * @param eventExpired event recoveries with no snapshot complete within the maximum recovery time
     * @param eventAbandoned event recoveries given up because their snapshot complete went with a
     *     lost queue
     * @param eventCallerGone event recoveries not asked for because their caller had stopped waiting
     * @param eventStatusesDropped ended event recoveries whose status was forgotten before its five
     *     minutes, to keep at most 10,000
     * @param safetyNetResets sessions' queues the safety net replaced
     * @param safetyNetDropped messages a session had not processed when the safety net replaced its
     *     queue
     * @param safetyNetRequestsFailed recoveries the safety net asked for that the API did not accept,
     *     so it reset nothing
     * @param factsDropped reports to the recovery's thread - alives, a session's progress, a
     *     channel's loss - that its queue had no room for
     * @param factsFailed reports the recovery's thread failed on; it went on with the next
     */
    public record Recovery(
            long requested,
            long reissued,
            long failed,
            long timedOut,
            long abandoned,
            long completed,
            long unknownCompletions,
            long unknownProducers,
            long eventRequested,
            long eventRefused,
            long eventExpired,
            long eventAbandoned,
            long eventCallerGone,
            long eventStatusesDropped,
            long safetyNetResets,
            long safetyNetDropped,
            long safetyNetRequestsFailed,
            long factsDropped,
            long factsFailed) {}

    /**
     * One catalog the SDK keeps from the API. A value whose refresh fails is served stale for as long
     * as refreshes fail.
     *
     * @param name such as {@code market descriptions}
     * @param state degraded once a value still read has been served stale for an hour or more
     * @param servedStale reads served a value older than the refresh age
     * @param staleFor how long the stalest value still read has been served stale; zero when none is
     * @param failedFetches fetches that failed, refreshes and first loads alike
     * @param failing values whose last fetch failed
     * @param evictedForRoom values the size bound dropped
     */
    public record Catalog(
            String name,
            HealthState state,
            long servedStale,
            Duration staleFor,
            long failedFetches,
            long failing,
            long evictedForRoom) {}

    /**
     * The caches of matches, fixtures, competitors, players, tournaments and sports, and the loads
     * that warm them in the background.
     *
     * @param sideLoadsDropped background loads dropped because their queue was full; a reader loads
     *     what it needs itself
     * @param sideLoadsFailed background loads that failed
     * @param staleFetchesDiscarded fetch results thrown away because the entry was invalidated,
     *     cleared or dropped while they ran; the reader reads it again
     * @param invalidationsForgotten invalidations the size bound forgot while a fetch could still
     *     run; each stopped every fetch that started before it on no entry
     * @param liveStatesDropped matches' live states from the feed that the size bound dropped
     * @param dedupEvictions fixture changes forgotten for room within the hour a repeat of one is
     *     dropped in
     */
    public record Caches(
            long sideLoadsDropped,
            long sideLoadsFailed,
            long staleFetchesDiscarded,
            long invalidationsForgotten,
            long liveStatesDropped,
            long dedupEvictions) {}

    /**
     * The thread that runs the feed's own callbacks, this listener's and the raw API data's.
     *
     * @param controlDropped events about the feed's state dropped for want of room; the producers',
     *     the sessions' and the connection's latest state never is
     * @param telemetryDropped API call events and callback failure reports dropped, the oldest first,
     *     for want of room
     * @param rawDataDropped raw API responses dropped for want of room under their byte budget
     * @param callbackFailures callbacks that threw
     */
    public record Events(long controlDropped, long telemetryDropped, long rawDataDropped, long callbackFailures) {}
}
