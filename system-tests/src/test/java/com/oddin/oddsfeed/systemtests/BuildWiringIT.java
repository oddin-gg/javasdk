package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks the build itself, not the SDK: that the classpath the scenarios will use is sane
 * and that the repository order which keeps public coordinates coming from Central is intact.
 *
 * <p>It cannot prove that integration tests run at all - it is an *IT, so removing the failsafe
 * binding would simply stop it being executed. The CI job checks the failsafe summary for that.
 */
class BuildWiringIT {

  private static final String CENTRAL = "https://repo.maven.apache.org/maven2";
  private static final String PACKAGES = "https://maven.pkg.github.com/oddin-gg/javasdk";

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
  void centralIsSearchedBeforeThePackagesRepository() throws Exception {
    // by URL, not by id: an entry named "central" pointing somewhere else would not protect
    // anything. Exactly these two, in this order - a third entry in front would be searched first.
    List<String> urls = declaredUrls(modulePom(), "repositories", "repository");

    assertThat(urls)
        .as("anything ahead of Central is searched before it, whatever it is called")
        .containsExactly(CENTRAL, PACKAGES);
  }

  @Test
  void pluginsAndExtensionsComeFromCentralOnly() throws Exception {
    // pluginRepositories are a separate list with the same shadowing problem
    assertThat(declaredUrls(modulePom(), "pluginRepositories", "pluginRepository"))
        .as("a plugin repository ahead of Central would be searched first for every plugin")
        .isEmpty();
    assertThat(declaredUrls(rootPom(), "pluginRepositories", "pluginRepository"))
        .as("a plugin repository in the parent applies to every module")
        .isEmpty();
  }

  @Test
  void theRootPomDeclaresNoRepositories() throws Exception {
    // a repository in the parent applies to every module, including ones that never touch the SDK
    Document root = parse(rootPom());

    assertThat(root.getElementsByTagName("repositories").getLength())
        .as("the parent must stay free of repositories, or every future module inherits them")
        .isZero();
  }

  private static Path basedir(String property) {
    String value = System.getProperty(property);
    // an unresolved property arrives as "", and Path.of("") is the working directory,
    // which would quietly point this test at the wrong POM
    assertThat(value)
        .as("%s is passed by the failsafe configuration; do not run this test outside Maven", property)
        .isNotBlank();
    return Path.of(value);
  }

  private static Path modulePom() {
    return basedir("module.basedir").resolve("pom.xml");
  }

  private static Path rootPom() {
    return basedir("root.basedir").resolve("pom.xml");
  }

  /** The URLs the POM really declares, in order: comments and profiles do not count. */
  private static List<String> declaredUrls(Path pom, String listTag, String itemTag)
      throws Exception {
    Element list = onlyChild(parse(pom).getDocumentElement(), listTag);
    List<String> urls = new ArrayList<>();
    if (list == null) {
      return urls;
    }
    NodeList children = list.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element element && itemTag.equals(element.getTagName())) {
        Element url = onlyChild(element, "url");
        if (url != null) {
          urls.add(url.getTextContent().trim());
        }
      }
    }
    return urls;
  }

  private static Element onlyChild(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element element && name.equals(element.getTagName())) {
        return element;
      }
    }
    return null;
  }

  private static Document parse(Path pom) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(pom.toFile());
  }

  @XmlRootElement(name = "ping")
  static class Ping {}
}
