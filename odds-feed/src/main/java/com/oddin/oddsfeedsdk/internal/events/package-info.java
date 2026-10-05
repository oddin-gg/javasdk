/**
 * The events dispatcher: the one thread every callback of the feed as a whole runs on - the
 * connection's state, producer status, fatal errors, failed callbacks, API calls and recovery
 * completion - fed through two bounded queues, so nobody who reports an event ever waits for the
 * client. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.events;

import org.jspecify.annotations.NullMarked;
