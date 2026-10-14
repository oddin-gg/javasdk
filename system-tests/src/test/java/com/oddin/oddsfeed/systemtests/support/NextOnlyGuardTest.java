package com.oddin.oddsfeed.systemtests.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * The scenarios only 1.0 can run never reach the 0.0.x run, and never go missing from the 1.0 one.
 * They are the classes of one package, each tagged by {@link NextOnly}; the legacy-sdk profile of
 * the POM leaves the tag out of both test plugins and the package out of the compilation. This test
 * runs against both lines and reads sources and the POM rather than classes, since on 0.0.x the
 * classes are not there to read: if a class were tagged and kept out of the package, or the tag
 * left out of a plugin, the 0.0.x run would fail on it, and so does this.
 */
class NextOnlyGuardTest {

    private static final String PACKAGE_PATH = NextOnly.PACKAGE.replace('.', '/');
    private static final String EXCLUDED = "<excludedGroups>${systemtests.excludedGroups}</excludedGroups>";

    private final Path module = Path.of(System.getProperty("module.basedir", ""));
    private final Path sources = module.resolve("src/test/java");

    @Test
    void theGroupIsNeverEmptyAndEveryClassInItIsAScenarioTaggedAsSuch() throws IOException {
        var directory = sources.resolve(PACKAGE_PATH);
        try (Stream<Path> files = Files.list(directory)) {
            var names =
                    files.map(file -> file.getFileName().toString()).sorted().toList();
            assertThat(names).as("the group's files").isNotEmpty().allMatch(name -> name.endsWith("IT.java"));
            for (var name : names) {
                // on a line of its own, so that a mention in a comment does not count
                assertThat(Files.readAllLines(directory.resolve(name)))
                        .as(name + " is tagged")
                        .anyMatch(line -> line.strip().equals("@NextOnly"));
            }
        }
    }

    @Test
    void noClassOutsideThePackageIsTagged() throws IOException {
        try (Stream<Path> files = Files.walk(sources)) {
            var tagged = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.startsWith(sources.resolve(PACKAGE_PATH)))
                    .filter(file -> !file.getFileName().toString().startsWith("NextOnly"))
                    .filter(file -> read(file).stream().anyMatch(NextOnlyGuardTest::tagsAsNextOnly))
                    .map(file -> sources.relativize(file).toString())
                    .toList();
            assertThat(tagged)
                    .as("classes tagged next-only outside " + NextOnly.PACKAGE + ", which 0.0.x would compile")
                    .isEmpty();
        }
    }

    @Test
    void theTagIsRecognisedWhateverWayItIsWritten() {
        assertThat(List.of("@NextOnly", "  @NextOnly  ", "@Tag(\"next-only\")", "@Tag(NextOnly.TAG)"))
                .as("the ways a class is tagged")
                .allMatch(NextOnlyGuardTest::tagsAsNextOnly);
        assertThat(List.of("@Tag(\"slow\")", "// see NextOnly", "class NextOnlyThing {}", "@Test"))
                .as("what is not a tag")
                .noneMatch(NextOnlyGuardTest::tagsAsNextOnly);
    }

    @Test
    void thePomLeavesTheGroupOutOfTheLegacyRunAndNothingOutOfTheOther() throws IOException {
        var pom = Files.readString(module.resolve("pom.xml"));
        assertThat(pom.split(Pattern.quote(EXCLUDED), -1))
                .as("surefire and failsafe each leave out the groups the property names: " + EXCLUDED)
                .hasSize(3);
        assertThat(pom)
                .as("the legacy-sdk profile leaves out the tag")
                .contains("<systemtests.excludedGroups>" + NextOnly.TAG + "</systemtests.excludedGroups>");
        assertThat(pom)
                .as("the legacy-sdk profile does not compile the package")
                .contains("<testExclude>" + PACKAGE_PATH + "/**</testExclude>");
        // the plain property, outside any profile, is what 1.0 runs with: nothing left out
        assertThat(pom).contains("<systemtests.excludedGroups></systemtests.excludedGroups>");
    }

    @Test
    void theRunLeavesOutExactlyWhatTheLineUnderTestCannotRun() throws IOException {
        var excluded = System.getProperty("systemtests.excludedGroups");
        assertThat(excluded).as("passed by the surefire configuration").isNotNull();
        var classes = classesOfTheGroup();
        if (KnownDifference.lineUnderTest() == KnownDifference.Line.LEGACY) {
            assertThat(excluded).as("groups left out on 0.0.x").isEqualTo(NextOnly.TAG);
            for (var name : classes) {
                assertThatThrownBy(() -> Class.forName(NextOnly.PACKAGE + "." + name))
                        .as(name + " must not be compiled against 0.0.x")
                        .isInstanceOf(ClassNotFoundException.class);
            }
        } else {
            assertThat(excluded).as("groups left out on 1.0").isBlank();
            for (var name : classes) {
                assertThat(tagsOf(NextOnly.PACKAGE + "." + name))
                        .as(name + " is compiled and tagged on 1.0")
                        .contains(NextOnly.TAG);
            }
        }
    }

    private List<String> classesOfTheGroup() throws IOException {
        try (Stream<Path> files = Files.list(sources.resolve(PACKAGE_PATH))) {
            return files.map(file -> file.getFileName().toString())
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> tagsOf(String className) {
        try {
            return AnnotationSupport.findRepeatableAnnotations(Class.forName(className), Tag.class).stream()
                    .map(Tag::value)
                    .toList();
        } catch (ClassNotFoundException e) {
            throw new AssertionError(className + " is not compiled", e);
        }
    }

    /** A line that tags a class as next-only: the annotation, or a {@code @Tag} of the literal or the constant. */
    private static boolean tagsAsNextOnly(String line) {
        return line.strip().matches("@(NextOnly|Tag\\(.*(next-only|NextOnly\\.TAG).*)");
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
