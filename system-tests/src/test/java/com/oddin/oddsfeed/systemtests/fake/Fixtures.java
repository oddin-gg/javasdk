package com.oddin.oddsfeed.systemtests.fake;

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
}
