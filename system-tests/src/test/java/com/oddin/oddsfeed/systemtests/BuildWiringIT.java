package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.KnownDifference.Line;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Checks the build itself, not the SDK: that the classpath the scenarios will use is sane, that
 * the repository setup which keeps public coordinates coming from Central is intact, and that a
 * published POM would carry a real version.
 *
 * <p>Against 0.0.x it pins the published jar by its digests and checks the javax runtime the old
 * jar needs; against 1.0 it checks that the SDK is the one this reactor packaged, by the version
 * it reports, and that its Jakarta runtime is there. Both SDKs compile against this class, so
 * whatever one of them lacks is reached by reflection.
 *
 * <p>It cannot prove that integration tests run at all - it is an *IT, so removing the failsafe
 * binding would simply stop it being executed. The CI job checks the failsafe summary for that.
 */
class BuildWiringIT {

    private static final String CENTRAL = "https://repo.maven.apache.org/maven2";
    private static final String PACKAGES = "https://maven.pkg.github.com/oddin-gg/javasdk";

    @Test
    void theSdkUnderTestIsOnTheClasspath() throws ClassNotFoundException {
        assertThat(sdkEntryPoint()).isNotNull();
    }

    @Test
    void theSdkArtifactsAreTheOnesWeKnow() throws Exception {
        assumeTrue(
                KnownDifference.lineUnderTest() == Line.LEGACY,
                "1.0 is built in this reactor: there is no published jar to compare it with");
        Path jar = resolvedSdkJar();
        // the POM sits beside the jar in the local repository and is read first: it decides the
        // transitive dependencies, so pinning only the jar would leave the graph open
        Path pom = jar.resolveSibling(jar.getFileName().toString().replaceAll("\\.jar$", ".pom"));
        String version = property("sdk.version");
        Properties known = knownSdkDigests();

        assertThat(known.getProperty(version + ".jar"))
                .as(
                        "no digest recorded for odds-feed %s; add the jar and pom digests to "
                                + "sdk-jar-checksums.properties once you know where they came from",
                        version)
                .isNotNull();
        assertThat(sha256(jar))
                .as("%s is not the odds-feed %s we know - check which repository served it", jar, version)
                .isEqualTo(known.getProperty(version + ".jar"));
        assertThat(pom).as("the POM Maven read to build the classpath").isRegularFile();
        assertThat(sha256(pom))
                .as("%s is not the POM we know: what it declares ends up on the classpath", pom)
                .isEqualTo(known.getProperty(version + ".pom"));
    }

    @Test
    void theVendoredSchemaAndFixturesAreOnTheTestClasspath() throws IOException {
        String source;
        try (InputStream in = BuildWiringIT.class.getResourceAsStream("/oddsfeedschema/SOURCE")) {
            assertThat(in)
                    .as("vendor/oddsfeedschema is not on the test classpath")
                    .isNotNull();
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(source)
                .as("SOURCE records which schema commit the copy came from")
                .containsPattern("(?m)^commit [0-9a-f]{40}$");

        // one of each kind the fakes and the golden tests will read
        for (String resource : List.of(
                "/oddsfeedschema/schema/feed/odds_change.xsd",
                "/oddsfeedschema/schema/rest/match_summary.xsd",
                "/oddsfeedschema/test/fixtures/feed/odds_change/odds_change_markets_only.xml",
                "/oddsfeedschema/test/fixtures/rest/match_summary/match_summary.xml")) {
            assertThat(BuildWiringIT.class.getResource(resource)).as(resource).isNotNull();
        }
    }

    @Test
    void theSdkIsTheOneThisReactorBuilt() throws Exception {
        assumeTrue(
                KnownDifference.lineUnderTest() == Line.NEXT,
                "0.0.x is the published jar, and theSdkArtifactsAreTheOnesWeKnow pins it");
        Path jar = resolvedSdkJar();
        String version = property("sdk.version");

        // the packaged jar, not target/classes: only the jar has the parser relocated into it
        assertThat(jar)
                .as("the SDK the tests loaded")
                .isEqualTo(basedir("root.basedir")
                        .resolve("odds-feed/target/odds-feed-" + version + ".jar")
                        .toAbsolutePath()
                        .normalize());
        // by reflection: 0.0.x has no such method, and this class compiles against both
        Object reported = sdkEntryPoint().getMethod("getSdkVersion").invoke(null);
        assertThat(reported)
                .as("the version the SDK reports to the API and the broker, against the one Maven resolved")
                .isEqualTo(version.endsWith("-SNAPSHOT") ? version.replaceFirst("-SNAPSHOT$", "-dev") : version);
    }

    @Test
    void theNewSdkCarriesItsOwnParser() {
        assumeTrue(KnownDifference.lineUnderTest() == Line.NEXT, "0.0.x reads XML with the JDK's parser");
        ClassLoader loader = getClass().getClassLoader();

        assertThat(loader.getResource("com/oddin/oddsfeedsdk/internal/woodstox/wstx/stax/WstxInputFactory.class"))
                .as("Woodstox, relocated into the SDK jar by the shade plugin")
                .isNotNull();
        // a plain copy would register itself as every StAX parser in the JVM, which no client of
        // 1.0 has unless it brings one itself
        assertThat(loader.getResource("com/ctc/wstx/stax/WstxInputFactory.class"))
                .as("an unrelocated Woodstox on the classpath")
                .isNull();
    }

    @Test
    void onlyOneArtifactProvidesTheJaxbApi() throws IOException {
        List<?> providers = Collections.list(
                getClass().getClassLoader().getResources(jaxbPackage().replace('.', '/') + "/JAXBContext.class"));

        assertThat(providers)
                .as("two copies of %s leave the winner to classpath order", jaxbPackage())
                .hasSize(1);
        if (KnownDifference.lineUnderTest() == Line.NEXT) {
            assertThat(getClass().getClassLoader().getResource("javax/xml/bind/JAXBContext.class"))
                    .as("javax.xml.bind in a 1.0 run: the legacy-sdk profile is active, and its jaxb-runtime 2.3.9 "
                            + "replaces the 4.0 runtime 1.0 needs; run with -Dsdk.next rather than setting "
                            + "sdk.version alone")
                    .isNull();
        }
    }

    @Test
    void aJaxbImplementationIsPresentAndCanUnmarshal() {
        assertThatCode(() -> {
                    // by reflection: each SDK brings one of the two packages, never both
                    ClassLoader loader = getClass().getClassLoader();
                    Class<?> context = Class.forName(jaxbPackage() + ".JAXBContext", true, loader);
                    Object unmarshaller = context.getMethod("createUnmarshaller")
                            .invoke(context.getMethod("newInstance", Class[].class)
                                    .invoke(null, (Object) new Class<?>[] {Ping.class}));
                    Object element = Class.forName(jaxbPackage() + ".Unmarshaller", true, loader)
                            .getMethod("unmarshal", Source.class, Class.class)
                            .invoke(unmarshaller, new StreamSource(new StringReader("<ping/>")), Ping.class);
                    Object read = Class.forName(jaxbPackage() + ".JAXBElement", true, loader)
                            .getMethod("getValue")
                            .invoke(element);
                    assertThat(read).isInstanceOf(Ping.class);
                })
                .as("the SDK needs a JAXB runtime, not just the API, to read feed and REST payloads")
                .doesNotThrowAnyException();
    }

    /** The XML binding API the SDK under test reads with: javax for 0.0.x, Jakarta for 1.0. */
    private static String jaxbPackage() {
        return KnownDifference.lineUnderTest() == Line.LEGACY ? "javax.xml.bind" : "jakarta.xml.bind";
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
    void nothingInDotMvnCanRedirectResolution() throws IOException {
        // maven.config is prepended to the command line, so "-s .mvn/settings.xml" there could
        // mirror every repository the POMs name; extensions.xml loads before the POM is even built
        try (var entries = Files.walk(basedir("root.basedir").resolve(".mvn"))) {
            assertThat(entries.filter(Files::isRegularFile)
                            .map(Path::getFileName)
                            .map(Path::toString))
                    .as(".mvn holds files that decide how Maven resolves, before any POM has a say")
                    .containsExactly("maven-wrapper.properties");
        }
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

    /** Looked up without running it: initialising an unverified class is the thing to avoid. */
    private static Class<?> sdkEntryPoint() throws ClassNotFoundException {
        return Class.forName("com.oddin.oddsfeedsdk.OddsFeed", false, BuildWiringIT.class.getClassLoader());
    }

    /** Where the SDK on the test classpath actually came from. */
    private static Path resolvedSdkJar() throws ClassNotFoundException, URISyntaxException {
        Path location = Path.of(sdkEntryPoint()
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());

        assertThat(location)
                .as("the SDK is a directory of classes, not a jar: Maven takes a reactor module's "
                        + "classes when the build stops before package, and they lack the relocated parser")
                .isRegularFile();
        return location;
    }

    private static Properties knownSdkDigests() throws IOException {
        Properties digests = new Properties();
        try (InputStream in = BuildWiringIT.class.getResourceAsStream("/sdk-jar-checksums.properties")) {
            assertThat(in)
                    .as("sdk-jar-checksums.properties is missing from the test resources")
                    .isNotNull();
            digests.load(in);
        }
        return digests;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static Path flattened(Path pom) {
        Path file = pom.resolveSibling(".flattened-pom.xml");
        // No freshness check on purpose. Flatten leaves the file alone when its content has not
        // changed, so its age says nothing, and a check on it failed every build run without clean.
        // A stale file can only linger locally, where it publishes nothing; CI starts from a clean
        // checkout and also inspects what an install actually writes.
        assertThat(file)
                .as("flatten-maven-plugin runs at process-resources; this should exist by now")
                .exists();
        return file;
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
        return declared(parse(pom), tag);
    }

    private static List<Element> declared(Document pom, String tag) {
        NodeList all = pom.getElementsByTagName(tag);
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
        return builder().parse(pom.toFile());
    }

    private static Document parse(String xml) throws Exception {
        return builder().parse(new InputSource(new StringReader(xml)));
    }

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder();
    }

    @Test
    void repositoryPoliciesAreReadTheWayMavenReadsThem() throws Exception {
        assertThat(enabled(repositoryFrom("<repository/>"), "releases"))
                .as("a policy nobody wrote down")
                .isTrue();
        assertThat(enabled(
                        repositoryFrom("<repository><releases><enabled>true</enabled>" + "</releases></repository>"),
                        "releases"))
                .isTrue();

        for (String no : List.of("false", "FALSE", "False", "yes", "")) {
            assertThat(enabled(
                            repositoryFrom(
                                    "<repository><releases><enabled>" + no + "</enabled></releases></repository>"),
                            "releases"))
                    .as("<enabled>%s</enabled>", no)
                    .isFalse();
        }
    }

    @Test
    void repositoriesAreFoundWhereverTheyHide() throws Exception {
        Document hidden = parse("""
        <project>
          <profiles><profile><id>ci</id>
            <repositories><repository><url>https://hidden.example</url></repository></repositories>
          </profile></profiles>
          <distributionManagement>
            <repository><url>https://deploy.example</url></repository>
          </distributionManagement>
        </project>
        """);

        assertThat(urlsOf(declared(hidden, "repository")))
                .as("a profile can be active by default, and Maven searches what it declares")
                .containsExactly("https://hidden.example");
    }

    private static Element repositoryFrom(String xml) throws Exception {
        return declared(parse("<project><repositories>" + xml + "</repositories></project>"), "repository")
                .getFirst();
    }

    /** Read with an expected type, so it needs no binding annotation from either package. */
    static class Ping {}
}
