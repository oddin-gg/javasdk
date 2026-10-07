package com.oddin.oddsfeedsdk.subscribe;

/** A part of the feed whose health the SDK watches. New in 1.0; later versions may add more. */
public enum HealthComponent {
    /** The broker client's consumer, which hands each delivery to its session. */
    CONSUMER,
    /** One session: its thread, which runs its callbacks, and how far behind it is. */
    SESSION,
    /** The thread that reads the producers' alives. */
    ALIVES,
    /** The thread that keeps the producers' status and asks for their recoveries. */
    RECOVERY,
    /** The thread that runs the callbacks of the feed as a whole, this listener's. */
    EVENTS,
    /** The SDK's timer thread, which runs its watch over the others. */
    TIMERS,
    /** The JVM's threads, for a deadlock among them. */
    THREADS,
    /** The market descriptions, void reasons and match statuses the SDK keeps from the API. */
    CATALOGS
}
