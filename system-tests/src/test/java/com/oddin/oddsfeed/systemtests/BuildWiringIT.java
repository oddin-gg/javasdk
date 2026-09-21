package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.annotation.XmlRootElement;
import org.junit.jupiter.api.Test;

/**
 * Checks the build itself, not the SDK: that the classpath the scenarios will use is sane
 * and that the repository order which keeps public coordinates coming from Central is intact.
 *
 * <p>It cannot prove that integration tests run at all - it is an *IT, so removing the failsafe
 * binding would simply stop it being executed. The CI job checks the failsafe summary for that.
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

  @Test
  void aJaxbImplementationIsPresentAndCanUnmarshal() {
    assertThatCode(() -> {
      JAXBContext context = JAXBContext.newInstance(Ping.class);
      Object read = context.createUnmarshaller().unmarshal(new StringReader("<ping/>"));
      assertThat(read).isInstanceOf(Ping.class);
    })
        .as("the old SDK needs a JAXB runtime, not just the API, to read feed and REST payloads")
        .doesNotThrowAnyException();
  }

  @Test
  void centralIsSearchedBeforeThePackagesRepository() throws IOException {
    String pom = Files.readString(Path.of("pom.xml"));
    int central = pom.indexOf("<id>central</id>");
    int packages = pom.indexOf("<id>oddin-github</id>");

    assertThat(central)
        .as("Central must be declared, or the packages repository is searched first")
        .isNotNegative();
    assertThat(central)
        .as("a repository declared earlier wins, and only odds-feed should come from packages")
        .isLessThan(packages);
  }

  @XmlRootElement(name = "ping")
  static class Ping {}
}
