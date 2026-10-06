package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.internal.catalog.MarketDescriptions;
import com.oddin.oddsfeedsdk.internal.entity.CacheSizes;
import com.oddin.oddsfeedsdk.internal.entity.Entities;
import com.oddin.oddsfeedsdk.internal.entity.MatchCaches;
import com.oddin.oddsfeedsdk.internal.entity.ProfileCaches;
import com.oddin.oddsfeedsdk.internal.loader.SideLoads;
import com.oddin.oddsfeedsdk.internal.message.MessageFactory;
import com.oddin.oddsfeedsdk.internal.message.Routes;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithOdds;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The warm path's steps after the decode, per odds change: the cache write, which turns the
 * message's match status into a write of the match's live state and makes it, and the entity
 * build, which builds the message the client gets - its event and every market and outcome - as a
 * session's dispatcher does before the callback. Names are not read: they are read from the
 * catalog only when the client asks. Nothing here calls the API.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class WarmPathBenchmark {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Param({"20", "150", "500"})
    public int markets;

    private byte[] body = new byte[0];
    private @Nullable OFOddsChange message;
    private @Nullable OFSportEventStatus status;
    private @Nullable ExecutorService threads;
    private @Nullable ApiClient client;
    private @Nullable SideLoads sideLoads;
    private @Nullable MatchCaches matches;
    private @Nullable MessageFactory factory;
    private @Nullable Producer producer;
    private final RoutingKeyInfo route = Routes.parse("hi.-.live.odds_change.5.od:match.198314.-");
    private final Instant receivedAt = Instant.now();
    /** Each write a newer message than the last, so each one writes. */
    private long timestamp = System.currentTimeMillis();

    @Setup
    public void prepare() throws DecodeException {
        body = Corpus.oddsChange(markets);
        OFOddsChange decoded = (OFOddsChange)
                FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES).decode(body);
        message = decoded;
        status = requireNonNull(decoded.getSportEventStatus());
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", "api.invalid")
                .setAccessToken("token")
                .build();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        threads = executor;
        var api = new ApiClient(configuration, ApiEvents.NONE);
        client = api;
        var loads = new SideLoads(1, 0, TIMEOUT);
        sideLoads = loads;
        var sizes = CacheSizes.from(configuration);
        var profiles = new ProfileCaches(api, TIMEOUT, sizes, executor);
        var caches = new MatchCaches(api, profiles, loads, TIMEOUT, sizes, Locale.ENGLISH, executor);
        matches = caches;
        var entities = new Entities(
                caches, profiles, (id, locales) -> null, ExceptionHandlingStrategy.THROW, TIMEOUT, executor, 16);
        factory = new MessageFactory(
                entities,
                new MarketDescriptions(api, TIMEOUT, executor),
                ExceptionHandlingStrategy.THROW,
                Locale.ENGLISH);
        producer = requireNonNull(new Producers(producers()).getProducer(2));
    }

    @TearDown
    public void close() {
        requireNonNull(sideLoads).close();
        requireNonNull(client).close();
        requireNonNull(threads).shutdownNow();
    }

    /** The match status of one odds change written to the match's live state. */
    @Benchmark
    public boolean cacheWrite() {
        return requireNonNull(matches)
                .oddsChange(MATCH, 2, ++timestamp, Duration.ZERO, receivedAt, requireNonNull(status));
    }

    /** One odds change built as the client gets it, its markets and outcomes included. */
    @Benchmark
    public List<MarketWithOdds> entityBuild() {
        MessageFactory messages = requireNonNull(factory);
        SportEvent event = messages.event(route);
        var built = (OddsChange<?>) requireNonNull(messages.build(
                requireNonNull(message),
                event,
                requireNonNull(producer),
                body,
                new MessageTimestamp(timestamp, timestamp, timestamp, timestamp)));
        return built.getMarkets();
    }

    private static RAProducers producers() {
        var list = new RAProducers();
        var live = new RAProducer();
        live.setId(2);
        live.setName("live");
        live.setDescription("live feed");
        live.setApiUrl("https://api.invalid/v1/live");
        live.setActive(true);
        live.setScope("live");
        live.setStatefulRecoveryWindowInMinutes(4320);
        list.getProducer().add(live);
        return list;
    }
}
