package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
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
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.BasicMessage;
import com.oddin.oddsfeedsdk.mq.entities.EventMessage;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The message factory over the real caches, catalog and REST client, against the fake API: what a
 * test of the messages or the dispatchers needs, built the way the feed will build it.
 */
public final class MessageWorld implements AutoCloseable {

    public static final Duration TIMEOUT = Duration.ofSeconds(5);
    public static final URN MATCH = URN.parse("od:match:198314");

    public final FakeRestServer api;
    public final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    public final ApiClient client;
    public final ProfileCaches profiles;
    public final MatchCaches matches;
    public final Entities entities;
    public final MarketDescriptions catalog;
    public final MessageFactory messages;
    public final Producers producers;
    public final FeedDecoder decoder = FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES);
    private final SideLoads sideLoads = new SideLoads(1, 0, TIMEOUT);

    private MessageWorld(ExceptionHandlingStrategy strategy) {
        api = FakeRestServer.start();
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(TIMEOUT)
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        profiles = new ProfileCaches(client, TIMEOUT, CacheSizes.from(configuration), threads);
        matches = new MatchCaches(
                client, profiles, sideLoads, TIMEOUT, CacheSizes.from(configuration), Locale.ENGLISH, threads);
        entities = new Entities(matches, profiles, (id, locales) -> null, strategy, TIMEOUT, threads, 16);
        catalog = new MarketDescriptions(client, TIMEOUT, threads);
        messages = new MessageFactory(entities, catalog, strategy, Locale.ENGLISH);
        producers = new Producers(client.fetchProducers());
    }

    public static MessageWorld start() {
        return start(ExceptionHandlingStrategy.THROW);
    }

    public static MessageWorld start(ExceptionHandlingStrategy strategy) {
        return new MessageWorld(strategy);
    }

    /** The message decoded, as the SDK's decoder reads it. */
    public BasicMessage decode(String xml) throws DecodeException {
        return (BasicMessage) decoder.decode(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** The message the client gets for this XML, with the routing key the feed would send it with. */
    public EventMessage<SportEvent> build(String xml) throws DecodeException {
        BasicMessage message = decode(xml);
        RoutingKeyInfo route = route(FakeFeed.routingKey(xml));
        SportEvent event = messages.event(route);
        Producer producer = Objects.requireNonNull(producers.getProducer(message.getProduct()));
        return Objects.requireNonNull(messages.build(
                message,
                event,
                producer,
                xml.getBytes(StandardCharsets.UTF_8),
                new MessageTimestamp(message.getTimestamp(), 1, 2, 3)));
    }

    /** A fixture's message, from producer 2 whatever producer the fixture names. */
    public EventMessage<SportEvent> fixture(String name) throws DecodeException {
        return build(fromLiveProducer(Fixtures.read(name)));
    }

    public static String fromLiveProducer(String xml) {
        return xml.replaceFirst("product=\"\\d+\"", "product=\"2\"");
    }

    public static RoutingKeyInfo route(String routingKey) {
        return Routes.parse(routingKey);
    }

    @Override
    public void close() {
        sideLoads.close();
        threads.shutdownNow();
        client.close();
        api.close();
    }
}
