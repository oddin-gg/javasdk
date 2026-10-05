package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.internal.catalog.MarketDescriptions;
import com.oddin.oddsfeedsdk.internal.entity.Entities;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.BasicMessage;
import com.oddin.oddsfeedsdk.mq.entities.EventMessage;
import com.oddin.oddsfeedsdk.mq.entities.Market;
import com.oddin.oddsfeedsdk.mq.entities.MarketCancel;
import com.oddin.oddsfeedsdk.mq.entities.MarketStatus;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithOdds;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithSettlement;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeOdds;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeResult;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetCancel;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlementMarket;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetStop;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFavourite;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFMarket;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChangeMarket;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOutcomeActive;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFResult;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetCancel;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the messages the client gets from what the decoder made of the feed's XML, as 0.0.x built
 * them.
 *
 * <ul>
 *   <li>The event is the one the routing key names, in the default locale: a match, or a tournament
 *       with the sport the routing key names. It loads nothing until one of its getters is called.
 *   <li>A market's specifiers are its {@code name=value} pairs, separated by a pipe; one that is not
 *       a pair is logged and left out. Its name and its outcomes' names are read from the market
 *       description catalog when asked for (see {@link MarketNames}).
 *   <li>Unknown wire values behave as in 0.0.x: an unknown fixture change type reads as another
 *       change; a market status or an outcome result this SDK does not know, or none, makes the
 *       getter that meets it throw a {@link NullPointerException}, and the message is delivered all
 *       the same.
 * </ul>
 *
 * <p>Safe for concurrent use.
 */
public final class MessageFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MessageFactory.class);

    private final Entities entities;
    private final Naming naming;
    private final List<Locale> locales;

    public MessageFactory(
            Entities entities, MarketDescriptions catalog, ExceptionHandlingStrategy strategy, Locale defaultLocale) {
        this.entities = entities;
        this.naming = new Naming(catalog, entities, strategy, defaultLocale);
        this.locales = List.of(defaultLocale);
    }

    /**
     * The event the routing key names.
     *
     * @throws IllegalArgumentException when it names none, or a tournament without its sport
     * @throws IllegalStateException when it names an event that is neither a match nor a tournament
     */
    public SportEvent event(RoutingKeyInfo routingKey) {
        URN id = routingKey.getEventId();
        if (id == null) {
            throw new IllegalArgumentException("the routing key names no event: " + routingKey.getFullRoutingKey());
        }
        return switch (id.getType()) {
            case URN.TypeMatch -> entities.match(id, routingKey.getSportId(), locales);
            case URN.TypeTournament -> {
                URN sport = routingKey.getSportId();
                if (sport == null) {
                    throw new IllegalArgumentException("tournament message " + id + " without sport in routing key");
                }
                yield entities.tournament(id, sport, locales);
            }
            default -> throw new IllegalStateException("Unsupported SportEvent type: " + id.getType());
        };
    }

    /**
     * The message the client gets for {@code message}, null for one that is no such message: an alive
     * or a snapshot complete.
     *
     * @param raw the bytes it was decoded from
     */
    public @Nullable EventMessage<SportEvent> build(
            BasicMessage message, SportEvent event, Producer producer, byte[] raw, MessageTimestamp timestamp) {
        return switch (message) {
            case OFOddsChange odds ->
                new OddsChangeMessage(event, odds, raw, producer, timestamp, () -> oddsMarkets(odds, event));
            case OFBetStop stop -> new BetStopMessage(event, stop, raw, producer, timestamp);
            case OFBetSettlement settlement ->
                new BetSettlementMessage(
                        event, settlement, raw, producer, timestamp, () -> settlementMarkets(settlement, event));
            case OFRollbackBetSettlement rollback ->
                new RollbackBetSettlementMessage(event, raw, producer, timestamp, rolledBack(rollback, event));
            case OFBetCancel cancel ->
                new BetCancelMessage(event, cancel, raw, producer, timestamp, cancelled(cancel, event));
            case OFRollbackBetCancel rollback ->
                new RollbackBetCancelMessage(event, rollback, raw, producer, timestamp, rolledBack(rollback, event));
            case OFFixtureChange change -> new FixtureChangeMessage(event, change, raw, producer, timestamp);
            default -> null;
        };
    }

    /** What the client gets for a message it could not read: the event its routing key names. */
    public UnparsableMessage<SportEvent> unparsable(
            RoutingKeyInfo routingKey, byte @Nullable [] raw, MessageTimestamp timestamp) {
        URN id = routingKey.getEventId();
        if (id == null) {
            throw new IllegalArgumentException("the routing key names no event: " + routingKey.getFullRoutingKey());
        }
        // a match whatever the event is, as 0.0.x built it
        return new UnparsableFeedMessage(entities.match(id, routingKey.getSportId(), locales), raw, timestamp);
    }

    // ------------------------------------------------------------------ markets

    private List<MarketWithOdds> oddsMarkets(OFOddsChange message, SportEvent event) {
        OFOddsChange.Odds odds = message.getOdds();
        if (odds == null) {
            return List.of();
        }
        var markets = new ArrayList<MarketWithOdds>(odds.getMarket().size());
        for (OFOddsChangeMarket market : odds.getMarket()) {
            Map<String, String> specifiers = specifiers(market.getSpecifiers());
            var names = new MarketNames(naming, market.getId(), specifiers, event);
            MarketStatus status = MarketStatus.fromFeedValue(market.getStatus());
            var outcomes = new ArrayList<OutcomeOdds>(market.getOutcome().size());
            for (OFOddsChangeMarket.OFOutcome outcome : market.getOutcome()) {
                outcomes.add(new OddsOutcome(
                        outcome.getId(),
                        names,
                        outcome.getOdds(),
                        outcome.getProbabilities(),
                        outcome.getActive() != OFOutcomeActive.INACTIVE));
            }
            markets.add(new OddsMarket(
                    market.getId(),
                    specifiers,
                    names,
                    status,
                    Collections.unmodifiableList(outcomes),
                    market.getFavourite() == OFFavourite.YES));
        }
        return Collections.unmodifiableList(markets);
    }

    private List<MarketWithSettlement> settlementMarkets(OFBetSettlement message, SportEvent event) {
        OFBetSettlement.OFOutcomes settled = message.getOutcomes();
        if (settled == null) {
            return List.of();
        }
        var markets = new ArrayList<MarketWithSettlement>(settled.getMarket().size());
        for (OFBetSettlementMarket market : settled.getMarket()) {
            Map<String, String> specifiers = specifiers(market.getSpecifiers());
            var names = new MarketNames(naming, market.getId(), specifiers, event);
            var outcomes = new ArrayList<OutcomeSettlement>(market.getOutcome().size());
            for (OFBetSettlementMarket.OFOutcome outcome : market.getOutcome()) {
                outcomes.add(new SettlementOutcome(
                        outcome.getId(), names, outcome.getVoidFactor(), result(outcome.getResult())));
            }
            markets.add(
                    new SettlementMarket(market.getId(), specifiers, names, Collections.unmodifiableList(outcomes)));
        }
        return Collections.unmodifiableList(markets);
    }

    private List<MarketCancel> cancelled(OFBetCancel message, SportEvent event) {
        var markets = new ArrayList<MarketCancel>(message.getMarket().size());
        for (OFMarket market : message.getMarket()) {
            Map<String, String> specifiers = specifiers(market.getSpecifiers());
            markets.add(new CancelMarket(
                    market.getId(),
                    specifiers,
                    new MarketNames(naming, market.getId(), specifiers, event),
                    market.getVoidReason(),
                    market.getVoidReasonId(),
                    market.getVoidReasonParams()));
        }
        return Collections.unmodifiableList(markets);
    }

    private List<Market> rolledBack(OFRollbackBetSettlement message, SportEvent event) {
        var markets = new ArrayList<Market>(message.getMarket().size());
        for (OFRollbackBetSettlement.OFRollbackBetSettlementMarket market : message.getMarket()) {
            markets.add(plain(market.getId(), market.getSpecifiers(), event));
        }
        return Collections.unmodifiableList(markets);
    }

    private List<Market> rolledBack(OFRollbackBetCancel message, SportEvent event) {
        var markets = new ArrayList<Market>(message.getMarket().size());
        for (OFRollbackBetCancel.OFRollbackBetCancelMarket market : message.getMarket()) {
            markets.add(plain(market.getId(), market.getSpecifiers(), event));
        }
        return Collections.unmodifiableList(markets);
    }

    private FeedMarket plain(int id, @Nullable String specified, SportEvent event) {
        Map<String, String> specifiers = specifiers(specified);
        return new FeedMarket(id, specifiers, new MarketNames(naming, id, specifiers, event));
    }

    /**
     * The result, as in 0.0.x.
     *
     * @throws NullPointerException for a result this SDK does not know, or none
     */
    private static OutcomeResult result(@Nullable OFResult result) {
        if (result == null || result == OFResult.UNKNOWN) {
            throw new NullPointerException("the feed sent an outcome result this SDK does not know, or none");
        }
        return switch (result) {
            case LOST -> OutcomeResult.LOST;
            case WON -> OutcomeResult.WON;
            case UNDECIDED_YET -> OutcomeResult.UNDECIDED_YET;
            case UNKNOWN -> throw new IllegalStateException("UNKNOWN was refused above");
        };
    }

    /** The {@code name=value} pairs, separated by a pipe; one that is not a pair is left out. */
    static Map<String, String> specifiers(@Nullable String specifiers) {
        if (specifiers == null || specifiers.isEmpty()) {
            return Map.of();
        }
        var pairs = new LinkedHashMap<String, String>();
        for (String pair : specifiers.split("\\|", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 0 || pair.indexOf('=', equals + 1) >= 0) {
                LOG.warn("Bad specifier {}", pair);
                continue;
            }
            pairs.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return Collections.unmodifiableMap(pairs);
    }
}
