package com.oddin.oddsfeed.fakes;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The fixtures vendored from the oddsfeedschema repository, read from the test classpath. */
public final class Fixtures {

    private static final String ROOT = "/oddsfeedschema/test/fixtures/";

    private Fixtures() {}

    /** A fixture by its path under the fixtures directory, e.g. {@code "rest/producers/producers.xml"}. */
    public static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name + " under " + ROOT);
            }
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code fixture} with {@code from} replaced by {@code to}, for a scenario that needs the
     * fixture changed in one place. Fails when there is no {@code from}, so a fixture refresh cannot
     * quietly turn the scenario into one that sends something else.
     */
    public static String replace(String fixture, String from, String to) {
        if (!fixture.contains(from)) {
            throw new IllegalArgumentException("no " + from + " in " + fixture);
        }
        return fixture.replace(from, to);
    }
}
