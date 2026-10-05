package com.oddin.oddsfeedsdk.internal.dispatch;

import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.internal.entity.MatchCaches;
import com.oddin.oddsfeedsdk.internal.entity.ProfileCaches;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.message.MessageFactory;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import java.time.InstantSource;

/**
 * What every session dispatcher of a feed shares: one of each per {@code OddsFeed}.
 *
 * @param matches where the feed's live status of a match is written, and a fixture change invalidates
 *     the match and its fixture
 * @param profiles where a fixture change of a tournament invalidates it
 * @param producers which producers the feed knows and the client takes messages from
 * @param fixtureChanges the fixture changes delivered, so each reaches the client once
 * @param offsets the producers' clock offsets, which the alive dispatcher measures
 * @param events where a failure is reported, to the client's {@code onCallbackFailure}
 * @param clock the SDK's clock, which a message's take time and age are read by
 */
public record Pipeline(
        FeedDecoder decoder,
        MessageFactory messages,
        MatchCaches matches,
        ProfileCaches profiles,
        ProducerManager producers,
        FixtureChanges fixtureChanges,
        ClockOffsets offsets,
        EventsDispatcher events,
        InstantSource clock) {}
