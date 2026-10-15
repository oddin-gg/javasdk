package com.oddin.oddsfeedsdk.internal.clock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
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
 * What measures a duration between two local events in the caches, the live state, the loaders and
 * the catalogs reads the feed's clock, monotonic side, and no other: not the system's wall clock, not
 * {@code System.nanoTime}, not a Caffeine ticker of its own. Read from the compiled classes, every
 * name they refer to, so a cache that reaches for a clock of its own - whose age a test, or a step of
 * the wall clock, would then not move with the feed's - fails the build.
 */
class CacheClockDependencyTest {

    private static final String ROOT = "com/oddin/oddsfeedsdk/internal/";

    /** The packages whose every class is checked, and the one class of another that keeps a cache. */
    private static final List<String> PACKAGES = List.of("cache", "catalog", "entity", "loader");

    private static final List<String> CLASSES = List.of("dispatch/FixtureChanges");

    /** What reads a clock directly, or makes a cache read one of its own. */
    private static final List<String> DIRECT_CLOCK_READS = List.of(
            "java/time/InstantSource",
            "java/time/Clock",
            "nanoTime",
            "currentTimeMillis",
            "systemTicker",
            "currentTimeNanos");

    @Test
    void theCachesReadNoClockButTheFeedClocksMonotonicSide() throws IOException, URISyntaxException {
        var checked = new ArrayList<String>();
        var violations = new ArrayList<String>();
        for (ClassModel model : classes()) {
            String name = model.thisClass().asInternalName();
            checked.add(name);
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof Utf8Entry text) {
                    for (String read : DIRECT_CLOCK_READS) {
                        if (text.stringValue().contains(read)) {
                            violations.add(name + " refers to " + text.stringValue());
                        }
                    }
                } else if (entry instanceof MemberRefEntry member
                        && member.owner().asInternalName().startsWith("java/time/")
                        && member.name().stringValue().equals("now")) {
                    violations.add(name + " calls " + member.owner().asInternalName() + ".now");
                } else if (entry instanceof MemberRefEntry member
                        && member.owner().asInternalName().equals(ROOT + "clock/FeedClock")
                        && member.name().stringValue().equals("wall")) {
                    violations.add(name + " reads the feed clock's wall source, which is for what the SDK publishes");
                }
            }
        }
        assertThat(checked)
                .as("classes read")
                .contains(
                        ROOT + "cache/LiveState",
                        ROOT + "cache/EntityCache",
                        ROOT + "cache/Entry",
                        ROOT + "catalog/Catalog",
                        ROOT + "entity/MatchCaches",
                        ROOT + "entity/ProfileCaches",
                        ROOT + "loader/Loader",
                        ROOT + "loader/SideLoads",
                        ROOT + "dispatch/FixtureChanges");
        assertThat(violations).isEmpty();
    }

    private static List<ClassModel> classes() throws IOException, URISyntaxException {
        Path root = Path.of(FeedClock.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .resolve(ROOT);
        var files = new ArrayList<Path>();
        for (String directory : PACKAGES) {
            try (Stream<Path> found = Files.list(root.resolve(directory))) {
                found.filter(f -> f.getFileName().toString().endsWith(".class")).forEach(files::add);
            }
        }
        for (String name : CLASSES) {
            try (Stream<Path> found = Files.list(root.resolve(name).getParent())) {
                String simple = Path.of(name).getFileName().toString();
                found.filter(f -> f.getFileName().toString().startsWith(simple))
                        .filter(f -> f.getFileName().toString().endsWith(".class"))
                        .forEach(files::add);
            }
        }
        var models = new ArrayList<ClassModel>();
        for (Path file : files) {
            models.add(ClassFile.of().parse(Files.readAllBytes(file)));
        }
        return models;
    }
}
