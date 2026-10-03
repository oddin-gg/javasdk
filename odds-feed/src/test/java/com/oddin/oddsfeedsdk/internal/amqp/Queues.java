package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Instant;

/** For tests in other packages: a session queue with deliveries in it. */
public final class Queues {

    private Queues() {}

    /** A queue of {@code capacity} holding {@code deliveries} deliveries of epoch 0. */
    public static SessionQueue holding(int capacity, int deliveries) {
        var queue = new SessionQueue(capacity);
        for (int tag = 1; tag <= deliveries; tag++) {
            queue.offer(new RawDelivery(new byte[0], 0, "key", tag, 0, Instant.EPOCH, null));
        }
        return queue;
    }
}
