package com.oddin.oddsfeedsdk.internal.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.Allocations;
import com.oddin.oddsfeedsdk.internal.log.Throttle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A market's specifiers are read as they were when a linked hash map held them - the pairs in the feed's order,
 * a name sent twice at its first place with its last value, what is no pair left out and counted - from an array
 * that allocates far less.
 */
class SpecifiersTest {

    private static volatile @Nullable Object sink = null;

    /** What {@code MessageFactory.specifiers} was before the array: the reference the array is held to. */
    private static Map<String, String> reference(@Nullable String specifiers, Throttle badPairs) {
        if (specifiers == null || specifiers.isEmpty()) {
            return Map.of();
        }
        var pairs = new LinkedHashMap<String, String>();
        for (String pair : specifiers.split("\\|", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 0 || pair.indexOf('=', equals + 1) >= 0) {
                badPairs.count();
                continue;
            }
            pairs.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return Collections.unmodifiableMap(pairs);
    }

    static Stream<String> inputs() {
        var sixteen = IntStream.range(0, Specifiers.MOST).mapToObj(i -> "k" + i + "=v" + i);
        var seventeen = IntStream.range(0, Specifiers.MOST + 1).mapToObj(i -> "k" + i + "=v" + i);
        var many = IntStream.range(0, 40).mapToObj(i -> i % 5 == 0 ? "bad" : "k" + i % 7 + "=v" + i);
        return Stream.concat(
                Stream.of(
                        "a=1",
                        "a=1|b=2",
                        "variant=way:two|way=two",
                        "map=1|threshold=10.5",
                        "a=1|a=2|b=3",
                        "b=3|a=1|b=4|a=2",
                        "bad|x=1",
                        "x=1|bad",
                        "x=1|",
                        "|x=1",
                        "a=1||b=2",
                        "|",
                        "||",
                        "bad",
                        "a=b=c|y=2",
                        "a==|y=2",
                        "=",
                        "=v",
                        "k=",
                        "k=|k=v",
                        "x=1|y=a=b|z=3",
                        "bad|a=b=c|x=|mapnr=2"),
                Stream.of(
                        sixteen.collect(Collectors.joining("|")),
                        seventeen.collect(Collectors.joining("|")),
                        many.collect(Collectors.joining("|"))));
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void theSameMapAsTheLinkedHashMapAndTheSameBadPairsCounted(String input) {
        var badPairs = new Throttle();
        var expectedBadPairs = new Throttle();
        Map<String, String> read = MessageFactory.specifiers(input, badPairs);
        Map<String, String> expected = reference(input, expectedBadPairs);

        assertThat(read).isEqualTo(expected);
        assertThat(expected).isEqualTo(read);
        assertThat(new ArrayList<>(read.entrySet()))
                .as("in the feed's order")
                .isEqualTo(new ArrayList<>(expected.entrySet()));
        assertThat(read.hashCode()).isEqualTo(expected.hashCode());
        assertThat(read.toString()).isEqualTo(expected.toString());
        assertThat(read).hasSize(expected.size());
        assertThat(read.isEmpty()).isEqualTo(expected.isEmpty());
        assertThat(new LinkedHashMap<>(read)).isEqualTo(expected);
        assertThat(badPairs.count()).as("bad pairs counted").isEqualTo(expectedBadPairs.count());
        for (var name : expected.keySet()) {
            assertThat(read.get(name)).isEqualTo(expected.get(name));
            assertThat(read.containsKey(name)).isTrue();
        }
        assertThat(read.get("no such name")).isNull();
        assertThat(read.containsKey("no such name")).isFalse();
    }

    @Test
    void whatItHoldsCannotBeChanged() {
        Map<String, String> read = MessageFactory.specifiers("a=1|b=2", new Throttle());
        assertThatThrownBy(() -> read.put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> read.put("a", "9")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> read.remove("a")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(read::clear).isInstanceOf(UnsupportedOperationException.class);
        var entries = read.entrySet().iterator();
        entries.next();
        assertThatThrownBy(entries::remove).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> read.entrySet().iterator().next().setValue("9"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(read).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
    }

    @Test
    void anIteratorEndsWhereTheMapDoes() {
        var entries =
                MessageFactory.specifiers("a=1", new Throttle()).entrySet().iterator();
        entries.next();
        assertThat(entries.hasNext()).isFalse();
        assertThatThrownBy(entries::next).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void aMarketsSpecifiersAllocateAFractionOfWhatAMapDid() {
        var input = "map=1|threshold=10.5";
        long array = Allocations.perCall(500, 3, () -> sink = MessageFactory.specifiers(input, new Throttle()));
        long map = Allocations.perCall(500, 3, () -> sink = reference(input, new Throttle()));
        // the Throttle both make is the same; what is left is the pairs: two strings each either way
        assertThat(array)
                .as("bytes allocated by the array's, against the map's %d", map)
                .isLessThan(map * 6 / 10);
    }
}
