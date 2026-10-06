package com.oddin.oddsfeedsdk.internal.session;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.lang.reflect.Proxy;
import java.util.Set;

/** Listeners and producers for the sessions' tests, which only pass them along or read their scopes. */
final class Doubles {

    private Doubles() {}

    static OddsFeedListener listener() {
        return proxy(OddsFeedListener.class);
    }

    static OddsFeedExtListener extListener() {
        return proxy(OddsFeedExtListener.class);
    }

    /** A producer that answers only its scopes. */
    static Producer producer(ProducerScope... scopes) {
        return (Producer) Proxy.newProxyInstance(
                Producer.class.getClassLoader(), new Class<?>[] {Producer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getProducerScopes")) {
                        return Set.of(scopes);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("ReferenceEquality") // a listener is equal to itself only, as a lambda would be
    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }
}
