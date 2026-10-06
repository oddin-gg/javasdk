package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import java.time.Instant;

/**
 * A session fell behind with the safety net's resets spent, or caught up again. A lagging session
 * still receives every message, only late; no producer goes down for it, since one slow session is
 * not a producer's fault. New in 1.0.
 *
 * @param lagging true when the session fell behind, false when it caught up
 * @param at when the SDK reported it, by its clock
 */
public record SessionLagChange(OddsFeedSession session, boolean lagging, Instant at) {}
