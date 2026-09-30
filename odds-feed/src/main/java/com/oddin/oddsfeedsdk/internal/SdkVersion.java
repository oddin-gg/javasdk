package com.oddin.oddsfeedsdk.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The SDK's version, as the API and the broker are told it, the way the Go SDK tells them: a
 * release reports its version, {@code 1.0.0}; any other build reports the version it is heading
 * for, marked {@code -dev}.
 */
public final class SdkVersion {

    /** What a build without a version reports: one from an IDE that skipped the build's resources. */
    static final String UNKNOWN = "0.0.0-dev";

    private static final String VERSION = reported(built());
    private static final String USER_AGENT = "oddin-javasdk/" + VERSION + " (java " + Runtime.version() + ")";

    private SdkVersion() {}

    /** {@code 1.0.0}, or {@code 1.0.1-dev} for a build that is not a release. */
    public static String version() {
        return VERSION;
    }

    /** The HTTP {@code User-Agent}: {@code oddin-javasdk/1.0.0 (java 25.0.1)}. */
    public static String userAgent() {
        return USER_AGENT;
    }

    /** A Maven version as reported: a snapshot becomes {@code -dev}, as the Go SDK marks one. */
    static String reported(@Nullable String built) {
        if (built == null || built.isBlank() || built.startsWith("${")) {
            return UNKNOWN;
        }
        return built.endsWith("-SNAPSHOT") ? built.substring(0, built.length() - "-SNAPSHOT".length()) + "-dev" : built;
    }

    private static @Nullable String built() {
        try (InputStream in = SdkVersion.class.getResourceAsStream("sdk.properties")) {
            if (in == null) {
                return null;
            }
            var properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the SDK's version", e);
        }
    }
}
