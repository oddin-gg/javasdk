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

    record Lookup(String name, int number, java.util.function.IntFunction<Object> read) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Lookup> lookups() {
        return Stream.of(
                new Lookup("OFMarketStatus", OFMarketStatus.ACTIVE.value(), OFMarketStatus::fromValue),
                new Lookup("OFFavourite", OFFavourite.YES.value(), OFFavourite::fromValue),
                new Lookup("OFOutcomeActive", OFOutcomeActive.ACTIVE.value(), OFOutcomeActive::fromValue),
                new Lookup("OFResult", OFResult.WON.value(), OFResult::fromValue),
                new Lookup("OFChangeType", OFChangeType.NEW.value(), OFChangeType::fromValue),
                new Lookup("OFEventStatus", OFEventStatus.LIVE.value(), OFEventStatus::fromValue));
    }

    @ParameterizedTest
    @MethodSource("lookups")
    void aLookupAllocatesNothing(Lookup lookup) {
        assertThat(lookup.read().apply(lookup.number())).isNotNull();
        assertThat(Allocations.perCall(500, 3, () -> sink = lookup.read().apply(lookup.number())))
                .as("bytes a lookup of %s allocates", lookup.name())
                .isZero();
    }
}
