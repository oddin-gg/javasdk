package com.oddin.oddsfeedsdk.internal.recovery;

/** For tests in other packages: a hook into a recovery actor's turns. */
public final class Actors {

    private Actors() {}

    /** Runs {@code hook} on the actor's thread, inside the handling of each fact it takes from now on. */
    public static void beforeHandle(RecoveryActor actor, Runnable hook) {
        actor.beforeHandle = fact -> hook.run();
    }

    /**
     * Runs {@code hook} on the actor's thread as it takes each of its ticks from now on, before the
     * tick is handled: once the hook has run {@code n + 1} times, {@code n} ticks have been handled
     * since it was set.
     */
    public static void beforeTick(RecoveryActor actor, Runnable hook) {
        actor.beforeHandle = fact -> {
            if (fact instanceof RecoveryActor.Fact.Tick) {
                hook.run();
            }
        };
    }
}
