package com.oddin.oddsfeedsdk.internal.rest;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import java.time.Duration;

/**
 * What opening the feed needs from the API before anything else: who the bookmaker is and which
 * producers there are. It keeps asking until the startup timeout, since an API that is briefly
 * away at startup should not stop the feed, then fails with an exception that says why. A refused
 * access token fails at once: asking again cannot change the answer. So does closing the client.
 *
 * @param bookmaker the whoami answer
 * @param producers the producer list
 */
public record Startup(RABookmakerDetail bookmaker, RAProducers producers) {

    /** The pause between rounds, so a round that failed at once does not hammer the API. */
    static final Duration PAUSE = Duration.ofSeconds(1);

    /**
     * Whoami and the producer list, each call bounded by the startup deadline as well as its own.
     *
     * @throws InitException when the API refuses the token, or both do not succeed in time
     */
    public static Startup fetch(ApiClient api, Duration timeout) {
        Deadline startup = Deadline.in(timeout);
        for (int round = 1; ; round++) {
            ApiException failure;
            try {
                RABookmakerDetail bookmaker = api.fetchWhoAmI(api.deadline().earlier(startup));
                RAProducers producers = api.fetchProducers(api.deadline().earlier(startup));
                return new Startup(bookmaker, producers);
            } catch (ApiException e) {
                failure = e;
            }
            if (api.isClosed()) {
                throw new InitException("Failed to init odds feed: the feed was closed", failure);
            }
            if (HttpStatusException.refused(failure)) {
                throw new InitException(
                        "Failed to init odds feed: the API refused the access token ("
                                + HttpStatusException.statusOf(failure) + ")",
                        failure);
            }
            Duration left = startup.remaining();
            if (left.compareTo(PAUSE) <= 0) {
                throw new InitException(
                        "Failed to init odds feed: whoami and the producer list did not succeed within "
                                + timeout.toMillis() + " ms, " + round + " rounds",
                        failure);
            }
            try {
                if (api.closedWithin(PAUSE)) {
                    throw new InitException("Failed to init odds feed: the feed was closed", failure);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InitException("Failed to init odds feed: interrupted", failure);
            }
        }
    }
}
