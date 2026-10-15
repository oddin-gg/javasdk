package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.internal.rest.ApiCall;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The REST client's one listener, telling each of the feed's in turn: the events dispatcher first,
 * then the health. One that throws is logged, and the next is told all the same.
 */
public final class ApiTee implements ApiEvents {

    private static final Logger LOG = LoggerFactory.getLogger(ApiTee.class);

    private final List<ApiEvents> told;

    /** @param told in the order they are told */
    public ApiTee(List<? extends ApiEvents> told) {
        this.told = List.copyOf(told);
    }

    @Override
    public void called(ApiCall call) {
        tell("called", listener -> listener.called(call));
    }

    @Override
    public void refused(ApiCall call) {
        tell("refused", listener -> listener.refused(call));
    }

    @Override
    public void received(URI uri, Object decoded, byte[] body) {
        tell("received", listener -> listener.received(uri, decoded, body));
    }

    private void tell(String event, Consumer<ApiEvents> tell) {
        for (ApiEvents listener : told) {
            try {
                tell.accept(listener);
            } catch (RuntimeException e) {
                LOG.error("The API listener threw on {}; the others are told", event, e);
            }
        }
    }
}
