package com.oddin.oddsfeedsdk.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which producers an interest takes messages from, as in 0.0.x. */
class MessageInterestTest {

    private static final Producer PREMATCH = producer(ProducerScope.PREMATCH);
    private static final Producer LIVE = producer(ProducerScope.LIVE);

    @Test
    void theScopedInterestsTakeOnlyTheirProducersAndTheRestTakeAll() {
        Map<Long, Producer> producers = Map.of(1L, PREMATCH, 2L, LIVE);
        assertThat(MessageInterest.PREMATCH_ONLY.getPossibleSourceProducers(producers))
                .containsExactly(1L);
        assertThat(MessageInterest.LIVE_ONLY.getPossibleSourceProducers(producers))
                .containsExactly(2L);
        for (MessageInterest interest : Set.of(
                MessageInterest.ALL,
                MessageInterest.HI_PRIORITY_ONLY,
                MessageInterest.LOW_PRIORITY_ONLY,
                MessageInterest.SPECIFIED_MATCHES_ONLY,
                MessageInterest.SYSTEM_ALIVE_ONLY)) {
            assertThat(interest.getPossibleSourceProducers(producers))
                    .as("%s", interest)
                    .containsExactlyInAnyOrder(1L, 2L);
        }
        assertThat(MessageInterest.LIVE_ONLY.isProducerInScope(PREMATCH)).isFalse();
    }

    @Test
    void eachInterestKeepsItsRoutingKeys() {
        assertThat(MessageInterest.ALL.getRoutingKeys()).containsExactly("*.*.*.*.*.*.*");
        assertThat(MessageInterest.LIVE_ONLY.getRoutingKeys()).containsExactly("*.*.live.*.*.*.*");
        assertThat(MessageInterest.PREMATCH_ONLY.getRoutingKeys()).containsExactly("*.pre.*.*.*.*.*");
        assertThat(MessageInterest.HI_PRIORITY_ONLY.getRoutingKeys()).containsExactly("hi.*.*.*.*.*.*");
        assertThat(MessageInterest.LOW_PRIORITY_ONLY.getRoutingKeys()).containsExactly("lo.*.*.*.*.*.*");
        assertThat(MessageInterest.SYSTEM_ALIVE_ONLY.getRoutingKeys()).containsExactly("-.-.-.alive.#");
        assertThat(MessageInterest.SPECIFIED_MATCHES_ONLY.getRoutingKeys()).isEmpty();
    }

    /** A producer that answers only its scopes; nothing else is asked. */
    private static Producer producer(ProducerScope scope) {
        return (Producer) Proxy.newProxyInstance(
                Producer.class.getClassLoader(), new Class<?>[] {Producer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getProducerScopes")) {
                        return Set.of(scope);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
