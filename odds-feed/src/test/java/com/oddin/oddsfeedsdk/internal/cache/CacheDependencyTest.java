package com.oddin.oddsfeedsdk.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The caches depend on nothing that fetches or waits: no loader, no REST client, no broker. Read
 * from the compiled classes, every name and descriptor they refer to - so a cache that called a
 * loader, even through a lambda or a field type, fails the build. The structural form of the rule
 * that caches never call loaders; the deadlock tests are the second net.
 */
class CacheDependencyTest {

    private static final List<String> FORBIDDEN = List.of(
            "com/oddin/oddsfeedsdk/internal/loader/",
            "com/oddin/oddsfeedsdk/internal/rest/",
            "com/oddin/oddsfeedsdk/internal/amqp/",
            "java/net/http/",
            "com/rabbitmq/");

    @Test
    void theCachesDependOnNoLoaderRestClientOrBroker() throws IOException, URISyntaxException {
        Path classes = Path.of(EntityCache.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .resolve("com/oddin/oddsfeedsdk/internal/cache");
        var checked = new ArrayList<String>();
        var violations = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                String name = model.thisClass().asInternalName();
                checked.add(name);
                for (PoolEntry entry : model.constantPool()) {
                    if (entry instanceof Utf8Entry text) {
                        for (String forbidden : FORBIDDEN) {
                            if (text.stringValue().contains(forbidden)) {
                                violations.add(name + " refers to " + text.stringValue());
                            }
                        }
                    }
                }
            }
        }
        assertThat(checked).as("cache classes read").contains("com/oddin/oddsfeedsdk/internal/cache/EntityCache");
        assertThat(violations).isEmpty();
    }
}
