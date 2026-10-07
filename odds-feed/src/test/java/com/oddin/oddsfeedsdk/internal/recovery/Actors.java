package com.oddin.oddsfeedsdk.internal.recovery;

/** For tests in other packages: a hook into a recovery actor's turns. */
public final class Actors {

    private Actors() {}

    /** Runs {@code hook} on the actor's thread, inside the handling of each fact it takes from now on. */
    public static void beforeHandle(RecoveryActor actor, Runnable hook) {
        actor.beforeHandle = fact -> hook.run();
    }
}
