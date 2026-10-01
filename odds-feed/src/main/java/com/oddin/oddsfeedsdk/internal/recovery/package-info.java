/**
 * The recovery actor: every producer's liveness, the sessions' checkpoints, the recoveries in
 * flight, their caps, and the stale-message safety net, on one thread that nothing else touches.
 * Sessions, the alive dispatcher, the transport and the REST workers post facts to it; it posts
 * work out. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.recovery;

import org.jspecify.annotations.NullMarked;
