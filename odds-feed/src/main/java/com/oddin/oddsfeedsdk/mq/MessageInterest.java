package com.oddin.oddsfeedsdk.mq;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Which of the feed's messages a session takes. */
public enum MessageInterest {
    LIVE_ONLY(List.of("*.*.live.*.*.*.*")),
    PREMATCH_ONLY(List.of("*.pre.*.*.*.*.*")),
    HI_PRIORITY_ONLY(List.of("hi.*.*.*.*.*.*")),
    LOW_PRIORITY_ONLY(List.of("lo.*.*.*.*.*.*")),
    SPECIFIED_MATCHES_ONLY(List.of()),
    ALL(List.of("*.*.*.*.*.*.*")),
    SYSTEM_ALIVE_ONLY(List.of("-.-.-.alive.#"));

    // every value is a List.of, so immutable; the field is a List because 0.0.x's getter returns one
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<String> routingKeys;

    MessageInterest(List<String> routingKeys) {
        this.routingKeys = routingKeys;
    }

    /** The routing keys that select this interest's messages; the specified-matches keys are built per session. */
    public List<String> getRoutingKeys() {
        return routingKeys;
    }

    /** The producers whose messages this interest can receive. */
    public Set<Long> getPossibleSourceProducers(Map<Long, ? extends Producer> availableProducers) {
        return availableProducers.entrySet().stream()
                .filter(entry -> isProducerInScope(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public boolean isProducerInScope(Producer producer) {
        return switch (this) {
            case LIVE_ONLY -> producer.getProducerScopes().contains(ProducerScope.LIVE);
            case PREMATCH_ONLY -> producer.getProducerScopes().contains(ProducerScope.PREMATCH);
            default -> true;
        };
    }
}
