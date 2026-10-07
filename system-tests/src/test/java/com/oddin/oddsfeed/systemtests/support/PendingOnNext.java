package com.oddin.oddsfeed.systemtests.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.opentest4j.TestAbortedException;

/**
 * The scenarios 1.0 does not pass yet: the ones listed in {@code pending-on-1.0.txt}, in the test
 * resources. The list held the scenarios back while the feed was put together, and is empty since;
 * a scenario written ahead of the 1.0 behaviour it checks is listed until that behaviour lands.
 *
 * <p>Against 1.0 a listed test still runs. If it fails, it is aborted instead, with the reason the
 * list gives, so the run shows it as skipped and the failure as its cause. If it passes, it fails:
 * the line is out of date, and whoever made it pass takes it out of the list in the same change.
 * Against 0.0.x the list is ignored, and every test counts as usual.
 *
 * <p>Registered for every test in the module by JUnit's extension auto-detection, which
 * {@code junit-platform.properties} turns on for this class alone, so no scenario names it.
 * {@code PendingOnNextListTest} checks the list names tests that exist.
 */
public final class PendingOnNext
        implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler, AfterEachCallback {

    /** The list, on the test classpath. */
    static final String LIST = "/pending-on-1.0.txt";

    /** Where to edit it. */
    private static final String SOURCE = "system-tests/src/test/resources/pending-on-1.0.txt";

    /** The package the list's names are relative to. */
    static final String SCENARIOS = "com.oddin.oddsfeed.systemtests.";

    private static final String NO_REASON = "listed in pending-on-1.0.txt";

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        throw abortedIfPending(context, failure);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        throw abortedIfPending(context, failure);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        throw abortedIfPending(context, failure);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        throw abortedIfPending(context, failure);
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        throw abortedIfPending(context, failure);
    }

    /** Runs after the test's own {@code @AfterEach} methods, so a pass here is a pass of the whole test. */
    @Override
    public void afterEach(ExtensionContext context) {
        var entry = pendingEntry(context);
        if (entry.isPresent() && context.getExecutionException().isEmpty()) {
            var name = entry.get();
            throw new AssertionError(
                    name.contains("#")
                            ? name + " passes on 1.0: remove it from the pending list, " + SOURCE
                            : testName(context) + " passes on 1.0: remove " + name + " from the pending list, " + SOURCE
                                    + ", or list only the methods of it that still fail");
        }
    }

    private static Throwable abortedIfPending(ExtensionContext context, Throwable failure) {
        if (failure instanceof TestAbortedException) {
            // skipped on its own account, by an assumption: neither a pass nor a failure
            return failure;
        }
        return pendingEntry(context)
                .<Throwable>map(entry ->
                        new TestAbortedException("pending on 1.0 (" + entry + "): " + Entries.ALL.get(entry), failure))
                .orElse(failure);
    }

    /**
     * The entry that lists this test or its class, against 1.0 only. The list is looked up first,
     * so the line under test, which needs the failsafe configuration, is only asked for about a
     * listed test.
     */
    private static Optional<String> pendingEntry(ExtensionContext context) {
        var className = className(context.getRequiredTestClass());
        var method = context.getTestMethod().map(m -> className + "#" + m.getName());
        var entry = method.filter(Entries.ALL::containsKey)
                .or(() -> Optional.of(className).filter(Entries.ALL::containsKey));
        return entry.filter(listed -> KnownDifference.lineUnderTest() == KnownDifference.Line.NEXT);
    }

    private static String testName(ExtensionContext context) {
        var className = className(context.getRequiredTestClass());
        return context.getTestMethod().map(m -> className + "#" + m.getName()).orElse(className);
    }

    /** A class's name as the list writes it: relative to the scenarios' package. */
    static String className(Class<?> type) {
        var name = type.getName();
        return name.startsWith(SCENARIOS) ? name.substring(SCENARIOS.length()) : name;
    }

    /**
     * The list's entries and their reasons, in order. A line is a test class, or a class and one
     * of its methods as {@code Class#method}, optionally followed by {@code # reason}; a line that
     * starts with {@code #} is a comment, and blank lines are ignored.
     */
    static Map<String, String> parse(BufferedReader lines) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        var number = 0;
        for (var line = lines.readLine(); line != null; line = lines.readLine()) {
            number++;
            var text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            var parts = text.split("\\s+", 2);
            var entry = parts[0];
            var rest = parts.length > 1 ? parts[1] : "";
            if (!entry.matches("[\\w.$]+(#\\w+)?") || !(rest.isEmpty() || rest.startsWith("#"))) {
                throw new IllegalStateException(LIST + " line " + number
                        + ": expected a class or Class#method, then optionally # reason, but got: " + line);
            }
            var reason = rest.isEmpty() ? NO_REASON : rest.substring(1).strip();
            if (entries.put(entry, reason.isEmpty() ? NO_REASON : reason) != null) {
                throw new IllegalStateException(LIST + " line " + number + ": " + entry + " is listed twice");
            }
        }
        return entries;
    }

    /** Read once, on first use. */
    static final class Entries {
        static final Map<String, String> ALL = read();

        private Entries() {}

        private static Map<String, String> read() {
            try (InputStream in = PendingOnNext.class.getResourceAsStream(LIST)) {
                if (in == null) {
                    throw new IllegalStateException(LIST + " is missing from the test resources");
                }
                return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + LIST, e);
            }
        }
    }
}
