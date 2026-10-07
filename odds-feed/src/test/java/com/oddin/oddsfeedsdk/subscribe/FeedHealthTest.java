package com.oddin.oddsfeedsdk.subscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The health's counters as a metrics exporter reads them: every value, under its own name. */
class FeedHealthTest {

    private static final OddsFeedSession SESSION = new OddsFeedSession() {};

    @Test
    void everyValueHasItsOwnNameInAFixedOrder() {
        Map<String, Long> counters = health().counters();

        assertThat(counters)
                .containsExactly(
                        Map.entry("state", 1L),
                        Map.entry("component.consumer.state", 0L),
                        Map.entry("component.session.state", 1L),
                        Map.entry("component.catalogs.state", 2L),
                        Map.entry("transport.connected", 1L),
                        Map.entry("transport.reconnects", 2L),
                        Map.entry("alives.queued", 3L),
                        Map.entry("alives.handled", 4L),
                        Map.entry("alives.dropped", 5L),
                        Map.entry("alives.unreadable", 6L),
                        Map.entry("session.7.state", 1L),
                        Map.entry("session.7.lagging", 1L),
                        Map.entry("session.7.queue_depth", 8L),
                        Map.entry("session.7.queue_overflows", 9L),
                        Map.entry("session.7.epoch_discards", 10L),
                        Map.entry("session.7.skipped_acks", 11L),
                        Map.entry("session.7.handled", 12L),
                        Map.entry("session.7.unparsable", 13L),
                        Map.entry("session.7.oversized", 14L),
                        Map.entry("session.7.pipeline_failures", 15L),
                        Map.entry("session.7.callback_failures", 16L),
                        Map.entry("session.7.unknown_producers", 17L),
                        Map.entry("session.7.repeated_fixture_changes", 18L),
                        Map.entry("recovery.requested", 101L),
                        Map.entry("recovery.reissued", 102L),
                        Map.entry("recovery.failed", 103L),
                        Map.entry("recovery.timed_out", 104L),
                        Map.entry("recovery.abandoned", 105L),
                        Map.entry("recovery.completed", 106L),
                        Map.entry("recovery.unknown_completions", 107L),
                        Map.entry("recovery.unknown_producers", 108L),
                        Map.entry("recovery.event_requested", 109L),
                        Map.entry("recovery.event_refused", 110L),
                        Map.entry("recovery.event_expired", 111L),
                        Map.entry("recovery.event_abandoned", 112L),
                        Map.entry("recovery.event_caller_gone", 113L),
                        Map.entry("recovery.event_statuses_dropped", 114L),
                        Map.entry("recovery.safety_net_resets", 115L),
                        Map.entry("recovery.safety_net_dropped", 116L),
                        Map.entry("recovery.safety_net_requests_failed", 117L),
                        Map.entry("recovery.facts_dropped", 118L),
                        Map.entry("recovery.facts_failed", 119L),
                        Map.entry("catalog.market_descriptions.state", 2L),
                        Map.entry("catalog.market_descriptions.served_stale", 201L),
                        Map.entry("catalog.market_descriptions.stale_for_millis", 7_200_000L),
                        Map.entry("catalog.market_descriptions.failed_fetches", 202L),
                        Map.entry("catalog.market_descriptions.failing", 203L),
                        Map.entry("catalog.market_descriptions.evicted_for_room", 204L),
                        Map.entry("caches.side_loads_dropped", 301L),
                        Map.entry("caches.side_loads_failed", 302L),
                        Map.entry("caches.stale_fetches_discarded", 303L),
                        Map.entry("caches.invalidations_forgotten", 304L),
                        Map.entry("caches.live_states_dropped", 305L),
                        Map.entry("caches.dedup_evictions", 306L),
                        Map.entry("events.control_dropped", 401L),
                        Map.entry("events.telemetry_dropped", 402L),
                        Map.entry("events.raw_data_dropped", 403L),
                        Map.entry("events.callback_failures", 404L));
    }

    /** A value added to a group and not to the counters fails here, not in a client's dashboard. */
    @Test
    void everyNumberOrFlagOfEveryGroupIsACounter() {
        Map<String, Long> counters = health().counters();
        for (Class<?> group : FeedHealth.class.getDeclaredClasses()) {
            if (!group.isRecord()) {
                continue;
            }
            String prefix = prefixOf(group);
            Set<String> named = counters.keySet().stream()
                    .filter(name -> name.startsWith(prefix))
                    .collect(Collectors.toSet());
            long values = Arrays.stream(group.getRecordComponents())
                    .filter(component -> !Set.of("id", "session", "name").contains(component.getName()))
                    .count();
            assertThat(named).as("%s's counters", group.getSimpleName()).hasSize((int) values);
        }
    }

    @Test
    void itsMapsAndListsAreItsOwnAndCannotBeChanged() {
        var components = new EnumMap<HealthComponent, HealthState>(HealthComponent.class);
        components.put(HealthComponent.EVENTS, HealthState.HEALTHY);
        var health = new FeedHealth(
                HealthState.HEALTHY,
                components,
                new FeedHealth.Transport(false, 0),
                new FeedHealth.Alives(0, 0, 0, 0),
                List.of(),
                recovery(0),
                List.of(),
                new FeedHealth.Caches(0, 0, 0, 0, 0, 0),
                new FeedHealth.Events(0, 0, 0, 0),
                Instant.EPOCH);
        components.put(HealthComponent.CATALOGS, HealthState.DEGRADED);

        assertThat(health.components()).containsExactly(Map.entry(HealthComponent.EVENTS, HealthState.HEALTHY));
        assertThatThrownBy(() -> health.components().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> health.counters().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new FeedHealth(
                                HealthState.HEALTHY,
                                Map.of(),
                                health.transport(),
                                health.alives(),
                                List.of(),
                                health.recovery(),
                                List.of(),
                                health.caches(),
                                health.events(),
                                Instant.EPOCH)
                        .components())
                .as("none yet")
                .isEmpty();
    }

    private static String prefixOf(Class<?> group) {
        return switch (group.getSimpleName()) {
            case "Session" -> "session.7.";
            case "Catalog" -> "catalog.market_descriptions.";
            case "Caches" -> "caches.";
            default -> group.getSimpleName().toLowerCase(java.util.Locale.ROOT) + ".";
        };
    }

    private static FeedHealth health() {
        var components = new EnumMap<HealthComponent, HealthState>(HealthComponent.class);
        // out of order, as a caller may put them: the counters follow the components' order
        components.put(HealthComponent.CATALOGS, HealthState.STALLED);
        components.put(HealthComponent.CONSUMER, HealthState.HEALTHY);
        components.put(HealthComponent.SESSION, HealthState.DEGRADED);
        return new FeedHealth(
                HealthState.DEGRADED,
                components,
                new FeedHealth.Transport(true, 2),
                new FeedHealth.Alives(3, 4, 5, 6),
                List.of(new FeedHealth.Session(
                        7, SESSION, HealthState.DEGRADED, true, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)),
                recovery(100),
                List.of(new FeedHealth.Catalog(
                        "market descriptions", HealthState.STALLED, 201, Duration.ofHours(2), 202, 203, 204)),
                new FeedHealth.Caches(301, 302, 303, 304, 305, 306),
                new FeedHealth.Events(401, 402, 403, 404),
                Instant.EPOCH);
    }

    private static FeedHealth.Recovery recovery(long base) {
        long b = base;
        return base == 0
                ? new FeedHealth.Recovery(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                : new FeedHealth.Recovery(
                        b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13,
                        b + 14, b + 15, b + 16, b + 17, b + 18, b + 19);
    }
}
