package com.oddin.oddsfeedsdk.internal.rest;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail;
import com.oddin.oddsfeedsdk.schema.rest.v1.RACompetitorProfileEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixtureChangesEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixturesEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescriptions;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketVoidReasons;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchStatusDescriptions;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayerProfileEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAReplaySetContent;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScheduleEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportTournaments;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportsEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournamentInfo;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/**
 * Every endpoint the SDK calls, with the paths, parameters and pools 0.0.x used, over one {@link
 * RestTransport}. Each method makes one call under its own deadline and throws {@link
 * ApiException} when the call fails.
 *
 * <p>Recovery requests carry a request id the API deduplicates on, so they are retried like reads.
 * Replay control is not: repeating a play or a clear after the API had done it would do it twice.
 *
 * <p>Safe for concurrent use.
 */
public final class ApiClient implements AutoCloseable {

    private final RestTransport transport;
    private final @Nullable Integer nodeId;

    public ApiClient(OddsFeedConfiguration configuration, ApiEvents events) {
        this(configuration, new RestTransport(configuration, events));
    }

    /** With the TLS a test's fake server needs. */
    ApiClient(OddsFeedConfiguration configuration, ApiEvents events, SSLContext tls) {
        this(configuration, new RestTransport(configuration, events, tls));
    }

    /** Over a given transport, for a test. */
    ApiClient(OddsFeedConfiguration configuration, RestTransport transport) {
        this.transport = transport;
        this.nodeId = configuration.getSdkNodeId();
    }

    /** A deadline for one call: the HTTP client timeout from now. */
    Deadline deadline() {
        return transport.deadline();
    }

    boolean isClosed() {
        return transport.isClosed();
    }

    /** Waits this long, or until the client is closed: true when it was. */
    boolean closedWithin(Duration wait) throws InterruptedException {
        return transport.closedWithin(wait);
    }

    public RABookmakerDetail fetchWhoAmI() {
        return fetchWhoAmI(deadline());
    }

    RABookmakerDetail fetchWhoAmI(Deadline deadline) {
        return transport.get(Pool.RECOVERY, "/users/whoami", RABookmakerDetail.class, deadline);
    }

    public RAProducers fetchProducers() {
        return fetchProducers(deadline());
    }

    RAProducers fetchProducers(Deadline deadline) {
        return transport.get(Pool.RECOVERY, "/descriptions/producers", RAProducers.class, deadline);
    }

    public RASportsEndpoint fetchSports(Locale locale) {
        return get(Pool.CATALOG, "/sports/" + language(locale) + "/sports", RASportsEndpoint.class);
    }

    public RAMatchStatusDescriptions fetchMatchStatusDescriptions(Locale locale) {
        return get(
                Pool.CATALOG, "/descriptions/" + language(locale) + "/match_status", RAMatchStatusDescriptions.class);
    }

    public RAMarketDescriptions fetchMarketDescriptions(Locale locale) {
        return get(Pool.CATALOG, "/descriptions/" + language(locale) + "/markets", RAMarketDescriptions.class);
    }

    public RAMarketDescriptions fetchMarketDescriptionsWithDynamicOutcomes(
            int marketTypeId, String variant, Locale locale) {
        return get(
                Pool.CATALOG,
                "/descriptions/" + language(locale) + "/markets/" + marketTypeId + "/variants/" + segment(variant),
                RAMarketDescriptions.class);
    }

    public RAMarketVoidReasons fetchMarketVoidReasons() {
        return get(Pool.CATALOG, "/descriptions/void_reasons", RAMarketVoidReasons.class);
    }

    public RAFixtureChangesEndpoint fetchFixtureChanges(Locale locale) {
        return get(Pool.DATA, "/sports/" + language(locale) + "/fixtures/changes", RAFixtureChangesEndpoint.class);
    }

    public RAFixturesEndpoint fetchFixture(URN id, Locale locale) {
        return fetchFixture(id, locale, deadline());
    }

    /** Within a loader's deadline, which every call of one fetch shares. */
    public RAFixturesEndpoint fetchFixture(URN id, Locale locale, Deadline deadline) {
        return transport.get(
                Pool.DATA,
                "/sports/" + language(locale) + "/sport_events/" + segment(id) + "/fixture",
                RAFixturesEndpoint.class,
                deadline);
    }

    /** The pre-match schedule from {@code startIndex}, {@code limit} events. */
    public RAScheduleEndpoint fetchSchedule(int startIndex, int limit, Locale locale) {
        return get(
                Pool.DATA,
                "/sports/" + language(locale) + "/schedules/pre/schedule?start=" + startIndex + "&limit=" + limit,
                RAScheduleEndpoint.class);
    }

    public RAScheduleEndpoint fetchLiveMatches(Locale locale) {
        return get(Pool.DATA, "/sports/" + language(locale) + "/schedules/live/schedule", RAScheduleEndpoint.class);
    }

    /** The matches of one day, in UTC. */
    public RAScheduleEndpoint fetchMatches(LocalDate date, Locale locale) {
        return get(
                Pool.DATA,
                "/sports/" + language(locale) + "/schedules/" + date + "/schedule",
                RAScheduleEndpoint.class);
    }

    public RASportTournaments fetchTournaments(URN sportId, Locale locale) {
        return fetchTournaments(sportId, locale, deadline());
    }

    /** Within a loader's deadline, which every call of one fetch shares. */
    public RASportTournaments fetchTournaments(URN sportId, Locale locale, Deadline deadline) {
        return transport.get(
                Pool.DATA,
                "/sports/" + language(locale) + "/sports/" + segment(sportId) + "/tournaments",
                RASportTournaments.class,
                deadline);
    }

    public RATournamentInfo fetchTournament(URN id, Locale locale) {
        return fetchTournament(id, locale, deadline());
    }

    /** Within a loader's deadline, which every call of one fetch shares. */
    public RATournamentInfo fetchTournament(URN id, Locale locale, Deadline deadline) {
        return transport.get(
                Pool.DATA,
                "/sports/" + language(locale) + "/tournaments/" + segment(id) + "/info",
                RATournamentInfo.class,
                deadline);
    }

    public RACompetitorProfileEndpoint fetchCompetitorProfile(URN id, Locale locale) {
        return fetchCompetitorProfile(id, locale, deadline());
    }

    /** Within a loader's deadline, which every call of one fetch shares. */
    public RACompetitorProfileEndpoint fetchCompetitorProfile(URN id, Locale locale, Deadline deadline) {
        return transport.get(
                Pool.DATA,
                "/sports/" + language(locale) + "/competitors/" + segment(id) + "/profile",
                RACompetitorProfileEndpoint.class,
                deadline);
    }

    public RAPlayerProfileEndpoint fetchPlayerProfile(URN id, Locale locale) {
        return fetchPlayerProfile(id, locale, deadline());
    }

    /** Within a loader's deadline, which every call of one fetch shares. */
    public RAPlayerProfileEndpoint fetchPlayerProfile(URN id, Locale locale, Deadline deadline) {
        return transport.get(
                Pool.DATA,
                "/sports/" + language(locale) + "/players/" + segment(id) + "/profile",
                RAPlayerProfileEndpoint.class,
                deadline);
    }

    public RAMatchSummaryEndpoint fetchMatchSummary(URN id, Locale locale) {
        return fetchMatchSummary(id, locale, deadline());
    }

    /** Within a loader's deadline, which every call of one fetch shares. */
    public RAMatchSummaryEndpoint fetchMatchSummary(URN id, Locale locale, Deadline deadline) {
        return transport.get(
                Pool.DATA,
                "/sports/" + language(locale) + "/sport_events/" + segment(id) + "/summary",
                RAMatchSummaryEndpoint.class,
                deadline);
    }

    /** Asks the producer to send the odds of one event again. */
    public void postEventOddsRecovery(String producer, URN eventId, long requestId) {
        recover("/" + segment(producer) + "/odds/events/" + segment(eventId) + "/initiate_request", requestId, null);
    }

    /** Asks the producer to send the stateful messages of one event again. */
    public void postEventStatefulRecovery(String producer, URN eventId, long requestId) {
        recover(
                "/" + segment(producer) + "/stateful_messages/events/" + segment(eventId) + "/initiate_request",
                requestId,
                null);
    }

    /** Asks the producer for everything since {@code after}, or for a full snapshot when it is null. */
    public void postRecovery(String producer, long requestId, @Nullable Instant after) {
        recover("/" + segment(producer) + "/recovery/initiate_request", requestId, after);
    }

    public RAReplaySetContent fetchReplaySetContent() {
        return get(Pool.DATA, "/replay" + new Query().node(nodeId), RAReplaySetContent.class);
    }

    public void putReplayEvent(URN eventId) {
        replay("PUT", "/replay/events/" + segment(eventId) + new Query().node(nodeId));
    }

    public void deleteReplayEvent(URN eventId) {
        replay("DELETE", "/replay/events/" + segment(eventId) + new Query().node(nodeId));
    }

    public void postReplayStop() {
        replay("POST", "/replay/stop" + new Query().node(nodeId));
    }

    public void postReplayClear() {
        replay("POST", "/replay/clear" + new Query().node(nodeId));
    }

    /** Starts the replay; what is null is left to the API. */
    public void postReplayStart(
            @Nullable Integer speed,
            @Nullable Integer maxDelay,
            @Nullable Boolean useReplayTimestamp,
            @Nullable Boolean runParallel,
            @Nullable String product) {
        replay(
                "POST",
                "/replay/play"
                        + new Query()
                                .node(nodeId)
                                .add("speed", speed)
                                .add("max_delay", maxDelay)
                                .add("use_replay_timestamp", useReplayTimestamp)
                                .add("run_parallel", runParallel)
                                .add("product", product));
    }

    @Override
    public void close() {
        transport.close();
    }

    private <T> T get(Pool pool, String path, Class<T> type) {
        return transport.get(pool, path, type, deadline());
    }

    private void recover(String path, long requestId, @Nullable Instant after) {
        var query = new Query()
                .add("request_id", requestId)
                .node(nodeId)
                .add("after", after == null ? null : after.toEpochMilli());
        transport.send("POST", Pool.RECOVERY, path + query, true, deadline());
    }

    private void replay(String method, String path) {
        transport.send(method, Pool.DATA, path, false, deadline());
    }

    private static String language(Locale locale) {
        return segment(locale.getLanguage());
    }

    private static String segment(URN urn) {
        return segment(urn.toString());
    }

    /**
     * {@code value} as one path segment: what a segment may hold as it is, the rest
     * percent-encoded. An id such as {@code od:match:1} is unchanged.
     */
    static String segment(String value) {
        return encode(value, "-._~!$&'()*+,;=:@");
    }

    /** {@code value} as a query parameter's value: as a segment, but without the query's separators. */
    static String queryValue(String value) {
        return encode(value, "-._~!$'()*,;:@");
    }

    private static String encode(String value, String asItIs) {
        var out = new StringBuilder(value.length());
        for (byte b : value.getBytes(UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || asItIs.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /** A query string; a parameter without a value is left out. */
    private static final class Query {
        private final StringBuilder query = new StringBuilder();

        Query node(@Nullable Integer node) {
            return add("node_id", node);
        }

        Query add(String name, @Nullable Object value) {
            if (value != null) {
                query.append(query.isEmpty() ? '?' : '&')
                        .append(name)
                        .append('=')
                        .append(queryValue(value.toString()));
            }
            return this;
        }

        @Override
        public String toString() {
            return query.toString();
        }
    }
}
