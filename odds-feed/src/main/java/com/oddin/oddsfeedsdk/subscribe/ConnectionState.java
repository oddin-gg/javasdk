package com.oddin.oddsfeedsdk.subscribe;

/** Where the feed's broker connection stands. New in 1.0. */
public enum ConnectionState {
    /** The first connect, as the feed opens. */
    CONNECTING,
    /** Connected, every session's channel open on the connection. */
    UP,
    /** Lost, not closed by the feed; reconnecting starts. */
    DOWN,
    /** Another try at reconnecting is coming. */
    RECOVERING
}
