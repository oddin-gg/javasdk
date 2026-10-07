package com.oddin.oddsfeedsdk.internal.xml;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * The jar as published: the module it names, Woodstox inside it under the SDK's own package,
 * registered as no one's StAX parser, and the decoder in it reading with it, hardened as in the unit
 * tests.
 */
class ShadedJarIT {

    private static final Path JAR = Path.of(required("shaded.jar"));

    private static final Path PUBLISHED_POM = Path.of(required("published.pom"));

    @Test
    void woodstoxIsInsideRelocatedAndRegistersNothing() throws IOException {
        try (var jar = new JarFile(JAR.toFile())) {
            var names = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(names)
                    .contains("com/oddin/oddsfeedsdk/internal/woodstox/wstx/stax/WstxInputFactory.class")
                    .contains("META-INF/licenses/woodstox-core.txt", "META-INF/licenses/stax2-api.txt");
            for (String library : List.of("com.fasterxml.woodstox/woodstox-core", "org.codehaus.woodstox/stax2-api")) {
                var entry = jar.getEntry("META-INF/maven/" + library + "/pom.properties");
                assertThat(entry)
                        .as("a dependency scanner finds the bundled %s by its Maven metadata", library)
                        .isNotNull();
                var properties = new java.util.Properties();
                try (var in = jar.getInputStream(entry)) {
                    properties.load(in);
                }
                assertThat(properties.getProperty("version")).isNotBlank();
            }
            assertThat(names)
                    .as("no copy under its own name, and no service file making it the application's parser")
                    .noneMatch(name -> name.startsWith("com/ctc/") || name.startsWith("org/codehaus/"))
                    .noneMatch(name -> name.startsWith("META-INF/services/"));
        }
    }

    /**
     * The module a client's module-info requires: named in the manifest, which the shading keeps,
     * and a module the JDK accepts with Woodstox's relocated packages in it.
     */
    @Test
    void theJarIsTheAutomaticModuleGgOddinOddsfeed() throws IOException {
        try (var jar = new JarFile(JAR.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"))
                    .isEqualTo("gg.oddin.oddsfeed");
        }
        var modules = ModuleFinder.of(JAR).findAll();
        assertThat(modules).hasSize(1);
        var descriptor = modules.iterator().next().descriptor();
        assertThat(descriptor.name()).isEqualTo("gg.oddin.oddsfeed");
        assertThat(descriptor.isAutomatic()).isTrue();
        assertThat(descriptor.packages())
                .contains("com.oddin.oddsfeedsdk", "com.oddin.oddsfeedsdk.internal.woodstox.wstx.stax");
    }

    @Test
    void thePublishedPomGivesClientsNoCopyOfTheirOwn() throws Exception {
        var pom = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance()
                .newDocumentBuilder()
                .parse(PUBLISHED_POM.toFile());
        var dependencies = pom.getElementsByTagName("dependency");
        var parsers = new java.util.ArrayList<String>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            var dependency = (org.w3c.dom.Element) dependencies.item(i);
            String artifact = text(dependency, "artifactId");
            if (artifact.equals("woodstox-core") || artifact.equals("stax2-api")) {
                parsers.add(artifact + " optional=" + text(dependency, "optional"));
            }
        }
        assertThat(parsers).containsExactly("woodstox-core optional=true");
    }

    @Test
    void theParserInTheJarStillTellsJaxbItInternsNames() throws Exception {
        try (var loader = new JarFirst(JAR, getClass().getClassLoader())) {
            var factory = (javax.xml.stream.XMLInputFactory)
                    loader.loadClass("com.oddin.oddsfeedsdk.internal.woodstox.wstx.stax.WstxInputFactory")
                            .getConstructor()
                            .newInstance();
            var reader = factory.createXMLStreamReader(new java.io.StringReader("<a/>"));
            // what JAXB asks: renamed by the relocation, the answer would be no, and JAXB intern every name
            assertThat(reader.getProperty("org.codehaus.stax2.internNames")).isEqualTo(true);
            assertThat(reader.getProperty("org.codehaus.stax2.internNsUris")).isEqualTo(true);
        }
    }

    @Test
    void theDecoderInTheJarReadsWithItAndKeepsItsLimits() throws Exception {
        try (var loader = new JarFirst(JAR, getClass().getClassLoader())) {
            Class<?> decoders = loader.loadClass(FeedDecoder.class.getName());
            assertThat(decoders.getClassLoader())
                    .as("the jar's, not the build's classes")
                    .isSameAs(loader);
            Object decoder = decoders.getMethod("lenient", int.class).invoke(null, FeedDecoder.DEFAULT_MAX_BYTES);
            Method decode = decoders.getMethod("decode", byte[].class);

            String alive = "<alive product=\"1\" timestamp=\"1\" subscribed=\"1\"/>";
            assertThat(decode.invoke(decoder, (Object) alive.getBytes(UTF_8))
                            .getClass()
                            .getSimpleName())
                    .isEqualTo("OFAlive");

            String deep = "<x>".repeat(FeedDecoder.MAX_DEPTH) + "</x>".repeat(FeedDecoder.MAX_DEPTH);
            String tooDeep = "<alive product=\"1\" timestamp=\"1\" subscribed=\"1\">" + deep + "</alive>";
            assertThat(failure(decode, decoder, tooDeep)).isEqualTo(DecodeException.class.getName());
            var names = new StringBuilder("<alive product=\"1\" timestamp=\"1\" subscribed=\"1\">");
            for (int i = 0; i <= XmlReader.MAX_NAMES; i++) {
                names.append("<n").append(i).append("/>");
            }
            assertThat(failure(decode, decoder, names.append("</alive>").toString()))
                    .as("too many distinct names")
                    .isEqualTo(DecodeException.class.getName());

            // text the decoder binds, so read in full only if lazy parsing is off
            Class<?> restDecoders = loader.loadClass(RestDecoder.class.getName());
            Object rest = restDecoders.getMethod("lenient", int.class).invoke(null, RestDecoder.DEFAULT_MAX_BYTES);
            Method restDecode = restDecoders.getMethod("decode", byte[].class);
            String badText = "<response response_code=\"NOT_FOUND\"><message>a&#0;b</message></response>";
            assertThat(failure(restDecode, rest, badText)).isEqualTo(DecodeException.class.getName());
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null) {
            throw new IllegalStateException(property + " is not set: run this through failsafe, which sets it");
        }
        return value;
    }

    private static String text(org.w3c.dom.Element parent, String child) {
        var children = parent.getElementsByTagName(child);
        return children.getLength() == 0
                ? ""
                : children.item(0).getTextContent().trim();
    }

    private static String failure(Method decode, Object decoder, String xml) throws IllegalAccessException {
        try {
            decode.invoke(decoder, (Object) xml.getBytes(UTF_8));
            return "nothing";
        } catch (InvocationTargetException e) {
            return String.valueOf(
                    e.getCause() == null ? null : e.getCause().getClass().getName());
        }
    }

    /** Loads the SDK's classes from the jar, and everything else - JAXB, JSpecify - as the build does. */
    private static final class JarFirst extends URLClassLoader {
        JarFirst(Path jar, ClassLoader parent) throws IOException {
            super(new URL[] {jar.toUri().toURL()}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.ctc.wstx.") || name.startsWith("org.codehaus.stax2.")) {
                // the build's own Woodstox: the jar must not need it
                throw new ClassNotFoundException(name + " is not relocated in the jar");
            }
            if (!name.startsWith("com.oddin.oddsfeedsdk.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : findClass(name);
            }
        }
    }
}
