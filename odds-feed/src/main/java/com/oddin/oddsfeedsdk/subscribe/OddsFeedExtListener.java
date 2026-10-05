package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import java.net.URI;

/**
 * Everything as it arrived: each feed message as its XML class, each API response as its XML class,
 * and each of them as the bytes it came in. The feed's messages are delivered on the thread of the
 * session that received them, before the session's own callback for the message; the API's responses
 * on the feed's events thread (see {@link GlobalEventsListener}), where the responses waiting for a
 * slow callback are bounded by their bytes: past that budget a response is dropped, and counted.
 */
public interface OddsFeedExtListener {
    void onRawFeedMessageReceived(
            UnparsedMessage message,
            MessageInterest messageInterest,
            RoutingKeyInfo routingKey,
            MessageTimestamp timestamp);

    /** {@code data} is the response's XML class, one of {@code com.oddin.oddsfeedsdk.schema.rest.v1}. */
    void onRawApiDataReceived(URI uri, Object data);

    /**
     * The feed message {@link #onRawFeedMessageReceived} was just called with, as the bytes the broker
     * delivered. New in 1.0; does nothing unless overridden.
     */
    default void onRawFeedMessageBytes(
            byte[] body, MessageInterest messageInterest, RoutingKeyInfo routingKey, MessageTimestamp timestamp) {}

    /**
     * The API response {@link #onRawApiDataReceived} was just called with, as the bytes the API
     * answered. New in 1.0; does nothing unless overridden.
     */
    default void onRawApiDataBytes(URI uri, byte[] body) {}
}
