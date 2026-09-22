package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Properties;
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
 * Checks the build itself, not the SDK: that the classpath the scenarios will use is sane, that
 * the repository setup which keeps public coordinates coming from Central is intact, and that a
 * published POM would carry a real version.
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
  void theSdkJarIsTheOneWeKnow() throws Exception {
    Path jar = resolvedSdkJar();
    String version = property("sdk.version");
    String expected = knownSdkDigests().getProperty(version);

    assertThat(expected)
        .as("no digest recorded for odds-feed %s; add one to sdk-jar-checksums.properties "
            + "after checking where the jar came from", version)
        .isNotNull();
    assertThat(sha256(jar))
        .as("%s is not the odds-feed %s we know - check which repository served it", jar, version)
        .isEqualTo(expected);
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
    List<Element> repositories = declared(modulePom(), "repository");

    assertThat(urlsOf(repositories))
        .as("anything ahead of Central is searched before it, whatever it is called")
        .containsExactly(CENTRAL, PACKAGES);
    for (Element repository : repositories) {
      // a disabled release policy takes Central out of the running as surely as deleting it
      assertThat(enabled(repository, "releases"))
          .as("%s must serve releases, or Maven falls through to the next repository", url(repository))
          .isTrue();
      assertThat(enabled(repository, "snapshots"))
          .as("%s must not serve snapshots: nothing here depends on one", url(repository))
          .isFalse();
    }
  }

  @Test
  void pluginsAndExtensionsComeFromCentralOnly() throws Exception {
    // pluginRepositories are a separate list with the same shadowing problem
    assertThat(declared(modulePom(), "pluginRepository"))
        .as("a plugin repository ahead of Central would be searched first for every plugin")
        .isEmpty();
    assertThat(declared(rootPom(), "pluginRepository"))
        .as("a plugin repository in the parent applies to every module")
        .isEmpty();
  }

  @Test
  void theRootPomDeclaresNoRepositories() throws Exception {
    // a repository in the parent applies to every module, including ones that never touch the SDK
    assertThat(declared(rootPom(), "repository"))
        .as("the parent must stay free of repositories, or every future module inherits them")
        .isEmpty();
  }

  @Test
  void aPublishedPomWouldCarryAResolvedVersion() throws Exception {
    // ${revision} is a build-time property: unflattened, a POM published from a release build
    // would name a version the artifact is not filed under
    String version = property("project.version");

    Element root = parse(flattened(rootPom())).getDocumentElement();
    assertThat(text(firstChild(root, "version")))
        .as("the parent POM that would be published")
        .isEqualTo(version);

    Element module = parse(flattened(modulePom())).getDocumentElement();
    assertThat(text(firstChild(firstChild(module, "parent"), "version")))
        .as("a module POM pointing at a parent version nobody published resolves to nothing")
        .isEqualTo(version);
  }

  /** Where the SDK on the test classpath actually came from. */
  private static Path resolvedSdkJar() throws ClassNotFoundException, URISyntaxException {
    Path location = Path.of(Class.forName("com.oddin.oddsfeedsdk.OddsFeed")
        .getProtectionDomain().getCodeSource().getLocation().toURI());

    assertThat(location)
        .as("once odds-feed is built in this reactor the SDK is a directory of classes, "
            + "and a digest of the published jar no longer says anything")
        .isRegularFile();
    return location;
  }

  private static Properties knownSdkDigests() throws IOException {
    Properties digests = new Properties();
    try (InputStream in =
        BuildWiringIT.class.getResourceAsStream("/sdk-jar-checksums.properties")) {
      assertThat(in).as("sdk-jar-checksums.properties is missing from the test resources").isNotNull();
      digests.load(in);
    }
    return digests;
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static Path flattened(Path pom) throws IOException {
    Path file = pom.resolveSibling(".flattened-pom.xml");
    assertThat(file)
        .as("flatten-maven-plugin runs at process-resources; this should exist by now")
        .exists();
    // it sits next to the POM rather than under target/, so without this a file left by an
    // earlier build would answer for a flatten execution that is no longer there
    assertThat(Files.getLastModifiedTime(file))
        .as("%s is left over from an earlier build", file)
        .isGreaterThanOrEqualTo(FileTime.from(buildStart().minusSeconds(2)));
    return file;
  }

  private static Instant buildStart() {
    return Instant.parse(property("build.timestamp"));
  }

  private static Path modulePom() {
    return basedir("module.basedir").resolve("pom.xml");
  }

  private static Path rootPom() {
    return basedir("root.basedir").resolve("pom.xml");
  }

  private static Path basedir(String name) {
    return Path.of(property(name));
  }

  private static String property(String name) {
    String value = System.getProperty(name);
    // an unresolved property arrives as "", and Path.of("") is the working directory,
    // which would quietly point this test at the wrong file
    assertThat(value)
        .as("%s is passed by the failsafe configuration; do not run this test outside Maven", name)
        .isNotBlank();
    return value;
  }

  /**
   * Every element with this tag anywhere in the POM except under distributionManagement, so a
   * block hidden inside a profile counts too. Profiles can be active by default, and Maven adds
   * their repositories to the ones it searches.
   */
  private static List<Element> declared(Path pom, String tag) throws Exception {
    NodeList all = parse(pom).getElementsByTagName(tag);
    List<Element> found = new ArrayList<>();
    for (int i = 0; i < all.getLength(); i++) {
      Element element = (Element) all.item(i);
      if (!hasAncestor(element, "distributionManagement")) {
        found.add(element);
      }
    }
    return found;
  }

  private static boolean hasAncestor(Node node, String tag) {
    for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
      if (parent instanceof Element element && tag.equals(element.getTagName())) {
        return true;
      }
    }
    return false;
  }

  private static List<String> urlsOf(List<Element> elements) {
    return elements.stream().map(BuildWiringIT::url).toList();
  }

  private static String url(Element repository) {
    return text(firstChild(repository, "url"));
  }

  /** As Maven reads it: a policy nobody wrote down is enabled, and the value is not case-sensitive. */
  private static boolean enabled(Element repository, String kind) {
    Element policy = firstChild(repository, kind);
    String value = policy == null ? null : text(firstChild(policy, "enabled"));
    return value == null || Boolean.parseBoolean(value);
  }

  private static Element firstChild(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element element && name.equals(element.getTagName())) {
        return element;
      }
    }
    return null;
  }

  private static String text(Element element) {
    return element == null ? null : element.getTextContent().trim();
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
