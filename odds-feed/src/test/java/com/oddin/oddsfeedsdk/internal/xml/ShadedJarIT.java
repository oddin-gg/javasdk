package com.oddin.oddsfeedsdk.internal.xml;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * The jar as published: Woodstox inside it under the SDK's own package, registered as no one's StAX
 * parser, and the decoder in it reading with it, hardened as in the unit tests.
 */
class ShadedJarIT {

    private static final Path JAR = Path.of(System.getProperty("shaded.jar", "target/odds-feed.jar"));

    @Test
    void woodstoxIsInsideRelocatedAndRegistersNothing() throws IOException {
        try (var jar = new JarFile(JAR.toFile())) {
            var names = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(names)
                    .contains("com/oddin/oddsfeedsdk/internal/woodstox/wstx/stax/WstxInputFactory.class")
                    .contains("META-INF/licenses/woodstox-core.txt", "META-INF/licenses/stax2-api.txt");
            assertThat(names)
                    .as("no copy under its own name, and no service file making it the application's parser")
                    .noneMatch(name -> name.startsWith("com/ctc/") || name.startsWith("org/codehaus/"))
                    .noneMatch(name -> name.startsWith("META-INF/services/"));
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
            String badText = "<alive product=\"1\" timestamp=\"1\" subscribed=\"1\"><x>a&#0;b</x></alive>";
            assertThat(failure(decode, decoder, badText)).isEqualTo(DecodeException.class.getName());
        }
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
