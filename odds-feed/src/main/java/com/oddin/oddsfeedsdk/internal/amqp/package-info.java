/**
 * The feed's broker: one connection per feed, a channel per session and one for the SDK's own alive
 * consumer, raw deliveries handed to bounded session queues, and reconnection. Nothing here decodes
 * a message or calls the client. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.amqp;

import org.jspecify.annotations.NullMarked;
