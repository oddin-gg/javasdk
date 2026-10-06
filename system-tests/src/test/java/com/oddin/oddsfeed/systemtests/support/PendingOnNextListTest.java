package com.oddin.oddsfeed.systemtests.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.annotation.Testable;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Every line of {@code pending-on-1.0.txt} names a test that exists, so a renamed or deleted
 * scenario cannot leave a line behind that no longer holds anything back.
 */
class PendingOnNextListTest {

    @Test
    void everyEntryNamesATestThatExists() {
        PendingOnNext.Entries.ALL.keySet().forEach(entry -> {
            var parts = entry.split("#", 2);
            var type = scenario(parts[0]);
            assertThat(type).as("%s: no such test class", entry).isNotNull();
            if (parts.length == 2) {
                assertThat(Arrays.stream(type.getDeclaredMethods())
                                .filter(method -> method.getName().equals(parts[1])))
                        .as("%s: no such test method", entry)
                        .anySatisfy(PendingOnNextListTest::isATest);
                assertThat(PendingOnNext.Entries.ALL)
                        .as("%s is listed, and its class as a whole too", entry)
                        .doesNotContainKey(parts[0]);
            } else {
                assertThat(type.getDeclaredMethods())
                        .as("%s has no tests to hold back", entry)
                        .anySatisfy(PendingOnNextListTest::isATest);
            }
        });
    }

    @Test
    void anEntryIsAClassOrAMethodWithAnOptionalReason() throws IOException {
        assertThat(parse("""
                # a comment

                FakeRestServerIT  # the feed is not assembled yet
                LocaleScenarioIT#aMatch #   with spaces around\s
                StartupScenarioIT
                """))
                .containsExactly(
                        Map.entry("FakeRestServerIT", "the feed is not assembled yet"),
                        Map.entry("LocaleScenarioIT#aMatch", "with spaces around"),
                        Map.entry("StartupScenarioIT", "listed in pending-on-1.0.txt"));
    }

    @Test
    void aLineThatIsNotAnEntryIsRefused() {
        for (var line : new String[] {"FakeRestServerIT the reason without its mark", "Foo#bar#baz", "Foo.bar()"}) {
            assertThatThrownBy(() -> parse(line))
                    .as(line)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("line 1");
        }
        assertThatThrownBy(() -> parse("FakeRestServerIT\nFakeRestServerIT # again"))
                .hasMessageContaining("line 2")
                .hasMessageContaining("listed twice");
    }

    private static Map<String, String> parse(String list) throws IOException {
        return PendingOnNext.parse(new BufferedReader(new StringReader(list)));
    }

    private static Class<?> scenario(String name) {
        try {
            return Class.forName(PendingOnNext.SCENARIOS + name, false, PendingOnNextListTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** {@code @Test}, or any other annotation JUnit runs a method for: they all carry {@code @Testable}. */
    private static void isATest(Method method) {
        assertThat(AnnotationSupport.isAnnotated(method, Testable.class))
                .as("%s is a test", method.getName())
                .isTrue();
    }
}
