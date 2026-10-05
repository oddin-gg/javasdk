/**
 * The dispatchers: a session's, which takes the session's deliveries one by one through decode,
 * cache write, build, the client's callback and the acknowledgement; and the alive dispatcher, which
 * decodes the SDK's own alives for the recovery actor. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.dispatch;

import org.jspecify.annotations.NullMarked;
