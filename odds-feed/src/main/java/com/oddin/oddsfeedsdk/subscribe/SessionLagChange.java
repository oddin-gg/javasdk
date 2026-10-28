package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import java.time.Instant;

/**
 * A session fell behind with the safety net's resets spent, or caught up again. A lagging session
 * still receives every message, only late. Lagging takes no producer down, since one slow session is
 * not a producer's fault; a producer the session processes later than the maximum inactivity is down
 * for that delay, as in 0.0.x, until the session is on time again. New in 1.0.
 *
 * @param lagging true when the session fell behind, false when it caught up
 * @param at when the SDK reported it, by its clock
 */
public record SessionLagChange(OddsFeedSession session, boolean lagging, Instant at) {}
