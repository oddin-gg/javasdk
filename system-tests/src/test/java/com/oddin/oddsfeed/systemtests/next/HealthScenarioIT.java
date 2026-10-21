package com.oddin.oddsfeed.systemtests.next;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.NextOnly;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code OddsFeed.getHealth()}, which 0.0.x does not have: what it reads before the feed starts,
 * while it runs, and once it is closed.
 */
@NextOnly
class HealthScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";

    /**
     * Before the start the health has no part and nothing counted; an open feed reads healthy, with
     * its broker connection and its session; and once closed it still reads, with the connection
     * gone and what it counted kept.
     */
    @Test
    void theHealthReadsBeforeTheStartWhileOpenAndOnceClosed() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start()) {
            var sdk = Sdk.against(rest, feed);
            FeedHealth opened;
            try {
                var before = sdk.oddsFeed().getHealth();
                assertThat(before.state()).as("state before the start").isEqualTo(HealthState.HEALTHY);
                assertThat(before.components()).as("parts before the start").isEmpty();
                assertThat(before.transport().connected())
                        .as("connected before the start")
                        .isFalse();
                assertThat(before.sessions()).as("sessions before the start").isEmpty();
                assertThat(counted(before))
                        .as("what the feed counted before the start")
                        .isNotEmpty()
                        .allSatisfy((name, value) -> assertThat(value).as(name).isZero());

                var received = sdk.open(MessageInterest.ALL);
                feed.publishFixture(ODDS_CHANGE);
                assertThat(received.next(OddsChange.class)).as("an odds change").isNotNull();

                var open = sdk.oddsFeed().getHealth();
                assertThat(open.state()).as("state of the open feed").isEqualTo(HealthState.HEALTHY);
                assertThat(open.components())
                        .as("parts of the open feed")
                        .containsEntry(HealthComponent.CONSUMER, HealthState.HEALTHY)
                        .containsEntry(HealthComponent.SESSION, HealthState.HEALTHY);
                assertThat(open.transport().connected())
                        .as("connected while open")
                        .isTrue();
                assertThat(open.sessions()).as("sessions of the open feed").hasSize(1);
                opened = open;
            } finally {
                sdk.close();
            }

            var closed = sdk.oddsFeed().getHealth();
            assertThat(closed.transport().connected())
                    .as("connected once closed")
                    .isFalse();
            assertThat(closed.sessions())
                    .as("sessions of the closed feed, with what they handled")
                    .singleElement()
                    .satisfies(session -> assertThat(session.handled()).isGreaterThanOrEqualTo(1));
            var sessionHandled = "session." + closed.sessions().getFirst().id() + ".handled";
            assertThat(closed.counters().get(sessionHandled))
                    .as(sessionHandled + " once closed")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(counted(closed))
                    .as("what the feed counted, once closed: none of it less than when open")
                    .isNotEmpty()
                    .allSatisfy((name, value) -> assertThat(value)
                            .as(name)
                            .isGreaterThanOrEqualTo(counted(opened).getOrDefault(name, 0L)));
        }
    }

    /**
     * What the health counted: every value of {@code counters()} that only grows, as the totals of the
     * transport, the alives, the sessions, the recovery, the catalogs, the caches and the events.
     */
    private static Map<String, Long> counted(FeedHealth health) {
        return health.counters().entrySet().stream()
                .filter(entry -> isTotal(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Whether a counter only grows. The ones that do not are named: a state, a flag, a queue's size,
     * the keys failing now and for how long a catalog has served stale; whatever else is added
     * counts, and is held to its total.
     */
    private static boolean isTotal(String name) {
        if (name.equals("state")
                || name.startsWith("component.")
                || name.equals("transport.connected")
                || name.equals("alives.queued")) {
            return false;
        }
        if (name.startsWith("session.") || name.startsWith("catalog.")) {
            var gauge = name.substring(name.lastIndexOf('.') + 1);
            return !GAUGES.contains(gauge);
        }
        return true;
    }

    /** The last part of the name of a session's or a catalog's value that comes and goes. */
    private static final Set<String> GAUGES = Set.of("state", "lagging", "queue_depth", "stale_for_millis", "failing");
}
