package com.oddin.oddsfeedsdk.stress;

import java.io.PrintStream;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * The seed every random choice of a stress run derives from. {@code -Dstress.seed=N} fixes it, so a
 * failure seen in CI reproduces locally; without it a fresh one is drawn once per JVM. A test class
 * that wants the seed printed when one of its tests fails adds {@code @ExtendWith(Seed.Report.class)}.
 */
final class Seed {

    static final String PROPERTY = "stress.seed";

    private static final long VALUE = from(System.getProperty(PROPERTY));

    private Seed() {}

    /** The seed of this run. */
    static long value() {
        return VALUE;
    }

    /** The value of a {@code stress.seed} setting, or a random one when there is none. */
    static long from(String setting) {
        return setting == null || setting.isBlank()
                ? ThreadLocalRandom.current().nextLong()
                : Long.parseLong(setting.strip());
    }

    /** A seed of its own for one thread or purpose, the same for the same master seed and name. */
    static long derive(long master, String name) {
        return mix(master ^ name.hashCode() * 0x9E3779B97F4A7C15L);
    }

    /** SplitMix64's finaliser: neighbouring inputs give unrelated outputs. */
    static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** Prints {@code STRESS SEED=<n>} when a test fails, and lets the failure through unchanged. */
    static final class Report implements TestExecutionExceptionHandler {

        private final PrintStream out;
        private final long seed;

        /** What JUnit instantiates. */
        Report() {
            this(System.err, VALUE);
        }

        Report(PrintStream out, long seed) {
            this.out = out;
            this.seed = seed;
        }

        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            out.println("STRESS SEED=" + seed + " (rerun with -D" + PROPERTY + "=" + seed + ")");
            throw failure;
        }
    }
}
