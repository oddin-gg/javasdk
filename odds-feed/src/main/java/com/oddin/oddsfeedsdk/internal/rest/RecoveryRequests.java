package com.oddin.oddsfeedsdk.internal.rest;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The recovery requests, as the recovery side makes them. {@link ApiClient} is the one that sends
 * them; a test has its own. Each call blocks for one API call and throws {@link ApiException} when
 * the API did not accept the request, so it runs on a REST worker, never on the recovery actor.
 */
public interface RecoveryRequests {

    /** Asks the producer for everything since {@code after}, or for a full snapshot when it is null. */
    void postRecovery(String producer, long requestId, @Nullable Instant after);

    /** Asks the producer to send the odds of one event again. */
    void postEventOddsRecovery(String producer, URN eventId, long requestId);

    /** Asks the producer to send the stateful messages of one event again. */
    void postEventStatefulRecovery(String producer, URN eventId, long requestId);
}
