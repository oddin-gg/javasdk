package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the build itself, not the SDK: that integration tests actually run and that the
 * classpath the scenarios will use is sane. It fails the moment someone removes the failsafe
 * binding, so `verify` cannot go green while silently skipping every *IT.
 */
class BuildWiringIT {

  @Test
  void theSdkUnderTestIsOnTheClasspath() throws ClassNotFoundException {
    assertThat(Class.forName("com.oddin.oddsfeedsdk.OddsFeed")).isNotNull();
  }

  @Test
  void onlyOneArtifactProvidesTheJaxbApi() throws IOException {
    List<?> providers = Collections.list(
        getClass().getClassLoader().getResources("javax/xml/bind/JAXBContext.class"));

    assertThat(providers)
        .as("two copies of javax.xml.bind leave the winner to classpath order")
        .hasSize(1);
  }
}
