package com.oddin.oddsfeed.systemtests.support;

import com.oddin.oddsfeedsdk.OddsFeed;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * Behaviour of the 0.0.x SDK that 1.0 changes, one constant per entry in
 * {@code system-tests/KNOWN-DIFFERENCES.md}, which says for each what 0.0.x does, what 1.0 does
 * and why.
 *
 * <p>A scenario asserts what both lines share as usual. Where they part, it hands both
 * expectations to the difference, and only the one for the SDK under test runs:
 *
 * <pre>{@code
 * KnownDifference.EVENT_RECOVERY_NOT_REPORTED.expect(
 *     () -> assertThat(events.pollEventRecovery(Duration.ofSeconds(1))).isEmpty(),  // 0.0.x
 *     () -> assertThat(events.pollEventRecovery(Received.DELIVERY)).isPresent());   // 1.0
 * }</pre>
 *
 * <p>So the old bug is pinned without being taken for the contract: the 0.0.x run shows the test
 * observes what it claims to, and the 1.0 run checks the fix. Where the 1.0 behaviour is still to
 * be decided, {@link #expectLegacy} checks 0.0.x and aborts the scenario on 1.x, so the 1.0 run
 * shows it as skipped with the reason until someone decides; it therefore goes last in its
 * scenario. The line under test comes from the {@code sdk.version} the build resolved, checked
 * against the jar the SDK was actually loaded from.
 */
public enum KnownDifference {
    EVENT_RECOVERY_NOT_REPORTED("KD-1", true),
    ALREADY_DOWN_PRODUCER_REPORTS_NOTHING("KD-2", false),
    UNKNOWN_PRODUCER_IS_MADE_UP("KD-3", true),
    FIXTURE_CHANGE_START_TIME_IS_ZERO("KD-4", true),
    VOID_REASON_IS_ALWAYS_NULL("KD-5", false),
    PIPE_SEPARATED_LISTS_ARE_NOT_SPLIT("KD-6", false),
    THROWING_CALLBACK_IS_REPORTED_AS_UNPARSABLE("KD-9", true),
    CATCH_GIVES_AN_EMPTY_COLLECTION("KD-10", true),
    FAILED_RECOVERY_IS_NOT_RETRIED("KD-11", true),
    NO_RECOVERY_AFTER_A_RECONNECT("KD-12", true),
    OLDER_MESSAGE_OVERWRITES_THE_STATUS("KD-13", true),
    STALE_MESSAGE_WRITES_THE_STATUS("KD-14", true),
    CLOSE_AFTER_A_FAILED_START_LOGS_AN_ERROR("KD-15", true),
    REFUSED_LOGIN_ESCAPES_AS_A_BROKER_EXCEPTION("KD-16", false);

    /** The SDK lines a scenario can run against. */
    public enum Line {
        /** 0.0.x, the Kotlin SDK. */
        LEGACY,
        /** 1.x, the rewrite. */
        NEXT
    }

    private final String id;
    private final boolean decided;

    KnownDifference(String id, boolean decided) {
        this.id = id;
        this.decided = decided;
    }

    /** The id of its entry in KNOWN-DIFFERENCES.md, e.g. {@code KD-1}. */
    public String id() {
        return id;
    }

    /** Whether the list says what 1.0 does, rather than "to be decided". */
    public boolean decided() {
        return decided;
    }

    /** Runs {@code legacy} against 0.0.x and {@code next} against 1.x. */
    public void expect(Check legacy, Check next) throws InterruptedException {
        if (!decided) {
            throw new IllegalStateException(id + " has no decided 1.0 behaviour; use expectLegacy");
        }
        check(lineUnderTest() == Line.LEGACY ? legacy : next);
    }

    /**
     * Runs {@code legacy} against 0.0.x; against 1.x, where what happens is still to be decided,
     * aborts the rest of the scenario - so call it last.
     */
    public void expectLegacy(Check legacy) throws InterruptedException {
        if (decided) {
            throw new IllegalStateException(id + " has a decided 1.0 behaviour; use expect and check it");
        }
        Assumptions.assumeTrue(
                lineUnderTest() == Line.LEGACY, id + ": what 1.0 does here is to be decided, see KNOWN-DIFFERENCES.md");
        check(legacy);
    }

    private void check(Check expectation) throws InterruptedException {
        try {
            expectation.run();
        } catch (AssertionError | RuntimeException e) {
            // an exception from the SDK means the listed behaviour did not hold just as much
            throw new AssertionError(
                    id + " (" + name() + "): the " + lineUnderTest()
                            + " behaviour KNOWN-DIFFERENCES.md lists did not hold - "
                            + (e instanceof AssertionError ? e.getMessage() : e.toString()),
                    e);
        }
    }

    /** The line of the SDK on the classpath. */
    public static Line lineUnderTest() {
        return UnderTest.LINE;
    }

    /** One expectation, as a lambda of assertions. */
    @FunctionalInterface
    public interface Check {
        void run() throws InterruptedException;
    }

    /** Worked out once, on first use, so a unit test that only reads the list needs no SDK. */
    private static final class UnderTest {

        static final Line LINE = line();

        private static Line line() {
            var version = System.getProperty("sdk.version");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException(
                        "sdk.version is passed by the failsafe configuration; " + "run the system tests through Maven");
            }
            // a build that resolved one version but put another jar first would check the wrong line
            var loadedFrom = loadedFrom();
            if (loadedFrom.endsWith(".jar") && !loadedFrom.equals("odds-feed-" + version + ".jar")) {
                throw new IllegalStateException(
                        "sdk.version is " + version + " but the SDK was loaded from " + loadedFrom);
            }
            if (version.startsWith("0.")) {
                return Line.LEGACY;
            }
            if (version.startsWith("1.")) {
                return Line.NEXT;
            }
            throw new IllegalStateException("no known differences for SDK version " + version);
        }

        private static String loadedFrom() {
            try {
                return Path.of(OddsFeed.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        .getFileName()
                        .toString();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("cannot tell where the SDK was loaded from", e);
            }
        }
    }
}
