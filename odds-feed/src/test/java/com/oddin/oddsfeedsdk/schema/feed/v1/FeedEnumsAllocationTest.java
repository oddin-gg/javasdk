package com.oddin.oddsfeedsdk.schema.feed.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.Allocations;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The feed's enums turn a number into a constant for every market and outcome of a message: they keep
 * their constants rather than copy them for each lookup, as {@code values()} does.
 */
class FeedEnumsAllocationTest {

    private static volatile @Nullable Object sink = null;

    /** A number no constant has. */
    private static final Integer UNNUMBERED = 12_345;

    /**
     * @param raw what the JAXB adapter calls, with the number boxed as it comes: a constant held in a
     *     field, so that boxing the number is not counted
     * @param boxed the number, boxed once
     * @param unknown what a number without a constant is
     */
    record Lookup(
            String name,
            int number,
            java.util.function.IntFunction<Object> read,
            Integer boxed,
            java.util.function.Function<Integer, Object> raw,
            Object unknown) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Lookup> lookups() {
        return Stream.of(
                new Lookup(
                        "OFMarketStatus",
                        OFMarketStatus.ACTIVE.value(),
                        OFMarketStatus::fromValue,
                        OFMarketStatus.ACTIVE.value(),
                        OFMarketStatus::fromRaw,
                        OFMarketStatus.UNKNOWN),
                new Lookup(
                        "OFFavourite",
                        OFFavourite.YES.value(),
                        OFFavourite::fromValue,
                        OFFavourite.YES.value(),
                        OFFavourite::fromRaw,
                        OFFavourite.UNKNOWN),
                new Lookup(
                        "OFOutcomeActive",
                        OFOutcomeActive.ACTIVE.value(),
                        OFOutcomeActive::fromValue,
                        OFOutcomeActive.ACTIVE.value(),
                        OFOutcomeActive::fromRaw,
                        OFOutcomeActive.UNKNOWN),
                new Lookup(
                        "OFResult",
                        OFResult.WON.value(),
                        OFResult::fromValue,
                        OFResult.WON.value(),
                        OFResult::fromRaw,
                        OFResult.UNKNOWN),
                new Lookup(
                        "OFChangeType",
                        OFChangeType.NEW.value(),
                        OFChangeType::fromValue,
                        OFChangeType.NEW.value(),
                        OFChangeType::fromRaw,
                        OFChangeType.UNKNOWN),
                new Lookup(
                        "OFEventStatus",
                        OFEventStatus.LIVE.value(),
                        OFEventStatus::fromValue,
                        OFEventStatus.LIVE.value(),
                        OFEventStatus::fromRaw,
                        OFEventStatus.UNKNOWN));
    }

    @ParameterizedTest
    @MethodSource("lookups")
    void aLookupAllocatesNothing(Lookup lookup) {
        assertThat(lookup.read().apply(lookup.number())).isNotNull();
        assertThat(Allocations.perCall(500, 3, () -> sink = lookup.read().apply(lookup.number())))
                .as("bytes a lookup of %s allocates", lookup.name())
                .isZero();
    }

    @ParameterizedTest
    @MethodSource("lookups")
    void aLookupOfTheNumberAsDecodedAllocatesNothing(Lookup lookup) {
        assertThat(lookup.raw().apply(lookup.boxed())).isEqualTo(lookup.read().apply(lookup.number()));
        assertThat(Allocations.perCall(500, 3, () -> sink = lookup.raw().apply(lookup.boxed())))
                .as("bytes a lookup of %s by the number as decoded allocates", lookup.name())
                .isZero();
    }

    @ParameterizedTest
    @MethodSource("lookups")
    void aNumberAsDecodedIsItsConstantNullForNoneAndUnknownWithoutOne(Lookup lookup) {
        assertThat(lookup.raw().apply(lookup.boxed()))
                .as("its constant")
                .isSameAs(lookup.read().apply(lookup.number()));
        assertThat(lookup.raw().apply(null)).as("none").isNull();
        assertThat(lookup.raw().apply(UNNUMBERED)).as("no constant").isSameAs(lookup.unknown());
    }
}
