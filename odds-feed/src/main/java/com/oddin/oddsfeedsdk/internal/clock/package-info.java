/**
 * The feed's one clock, read two ways: the wall time the SDK publishes and compares with the
 * producers' own clocks, and a monotonic reading for the durations measured between two local
 * events. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.clock;

import org.jspecify.annotations.NullMarked;
