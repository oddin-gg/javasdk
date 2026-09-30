package com.oddin.oddsfeedsdk.internal.producer;

import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.api.entities.RecoveryInfo;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The producers of the producer list, and the state the feed keeps for each: the client's side of
 * {@link ProducerManager}, and the setters the recovery side uses. The list is fixed at startup; the
 * state of each producer changes by whole records, so a reader sees one moment, never half of a
 * change.
 *
 * <p>A producer the list does not have is unknown, not made up as 0.0.x made it: {@link
 * #getProducer} returns null for it. The rest keeps 0.0.x's answers - not enabled, down, and
 * setters that change nothing - because a client calling them with such an id worked before.
 *
 * <p>Safe for concurrent use.
 */
public final class Producers implements ProducerManager {

    private final Map<Long, AtomicReference<ProducerState>> producers;
    private final InstantSource clock;

    public Producers(RAProducers list) {
        this(list, InstantSource.system());
    }

    Producers(RAProducers list, InstantSource clock) {
        this.clock = clock;
        var byId = new LinkedHashMap<Long, AtomicReference<ProducerState>>();
        for (RAProducer producer : list.getProducer()) {
            byId.put(
                    producer.getId(),
                    new AtomicReference<>(new ProducerState(
                            producer.getId(),
                            String.valueOf(producer.getName()),
                            String.valueOf(producer.getDescription()),
                            producer.isActive(),
                            String.valueOf(producer.getApiUrl()),
                            scopes(producer.getScope()),
                            producer.getStatefulRecoveryWindowInMinutes(),
                            producer.isActive(),
                            true,
                            0,
                            0,
                            0,
                            0,
                            null)));
        }
        this.producers = Collections.unmodifiableMap(byId);
    }

    /**
     * The scopes in the list's {@code prematch|live}. 0.0.x did not split it - Kotlin's {@code
     * split("\\|")} takes its argument literally - so a producer in both scopes had none.
     */
    static Set<ProducerScope> scopes(@Nullable String listed) {
        Set<ProducerScope> scopes = EnumSet.noneOf(ProducerScope.class);
        if (listed != null) {
            for (String scope : listed.split("\\|", -1)) {
                switch (scope.trim().toLowerCase(Locale.ROOT)) {
                    case "prematch" -> scopes.add(ProducerScope.PREMATCH);
                    case "live" -> scopes.add(ProducerScope.LIVE);
                    default -> {}
                }
            }
        }
        return scopes;
    }

    /** Every producer of the list, in its order; a new map each time, as 0.0.x gave. */
    @Override
    public Map<Long, Producer> getAvailableProducers() {
        var all = new LinkedHashMap<Long, Producer>();
        producers.forEach((id, state) -> all.put(id, new ProducerView(state, clock)));
        return all;
    }

    /** The producers the list has as active. */
    @Override
    public Map<Long, Producer> getActiveProducers() {
        var active = new LinkedHashMap<Long, Producer>();
        producers.forEach((id, state) -> {
            if (state.get().available()) {
                active.put(id, new ProducerView(state, clock));
            }
        });
        return active;
    }

    /** The producer, or null when the list does not have it. */
    @Override
    public @Nullable Producer getProducer(long id) {
        AtomicReference<ProducerState> state = producers.get(id);
        return state == null ? null : new ProducerView(state, clock);
    }

    @Override
    public void setProducerState(long id, boolean enabled) {
        update(id, state -> state.withEnabled(enabled));
    }

    /**
     * Where recovery of this producer starts, epoch millis, 0 for a full snapshot.
     *
     * @throws IllegalArgumentException when it is further back than the producer's stateful recovery
     *     window, with 0.0.x's message
     */
    @Override
    public void setProducerRecoveryFromTimestamp(long producerId, long timestamp) {
        AtomicReference<ProducerState> state = producers.get(producerId);
        if (state == null) {
            return;
        }
        if (timestamp != 0) {
            int window = state.get().statefulRecoveryWindowInMinutes();
            long requested = clock.millis() - timestamp;
            if (requested > Duration.ofMinutes(window).toMillis()) {
                throw new IllegalArgumentException(String.format(
                        "Last received message timestamp can not be more than '%s' minutes ago, producerId:%s"
                                + " timestamp:%s (max recovery = '%s' minutes ago)",
                        window, producerId, timestamp, window));
            }
        }
        state.updateAndGet(s -> s.withRecoveryFrom(timestamp));
    }

    @Override
    public boolean isProducerEnabled(long id) {
        AtomicReference<ProducerState> state = producers.get(id);
        return state != null && state.get().enabled();
    }

    @Override
    public boolean isProducerDown(long id) {
        AtomicReference<ProducerState> state = producers.get(id);
        return state == null || state.get().down();
    }

    /** Whether the producer list has this producer. */
    public boolean isKnown(long id) {
        return producers.containsKey(id);
    }

    public void setDown(long id, boolean down) {
        update(id, state -> state.withDown(down));
    }

    /**
     * When the last message from the producer arrived, epoch millis.
     *
     * @throws IllegalArgumentException unless the timestamp is positive
     */
    public void setLastMessageTimestamp(long id, long timestamp) {
        if (timestamp <= 0) {
            throw new IllegalArgumentException("a message timestamp is positive, was " + timestamp);
        }
        update(id, state -> state.withLastMessageTimestamp(timestamp));
    }

    public void setLastProcessedMessageGenTimestamp(long id, long timestamp) {
        update(id, state -> state.withLastProcessedMessageGenTimestamp(timestamp));
    }

    public void setLastAliveReceivedGenTimestamp(long id, long timestamp) {
        update(id, state -> state.withLastAliveReceivedGenTimestamp(timestamp));
    }

    public void setRecoveryInfo(long id, RecoveryInfo recovery) {
        update(id, state -> state.withRecoveryInfo(recovery));
    }

    private void update(long id, UnaryOperator<ProducerState> change) {
        AtomicReference<ProducerState> state = producers.get(id);
        if (state != null) {
            state.updateAndGet(change);
        }
    }
}
