package com.oddin.oddsfeedsdk.internal.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.Allocations;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A message makes the names of every market it carries, and most clients never read one. */
class MarketNamesAllocationTest {

    private static volatile @Nullable Object sink = null;

    @Test
    void aMarketsNamesMakeNoMapUntilANameIsRead() {
        try (var world = MessageWorld.start()) {
            var naming = new Naming(world.catalog, world.entities, ExceptionHandlingStrategy.THROW, Locale.ENGLISH);
            var event = world.messages.event(MessageWorld.route("hi.-.live.odds_change.5.od:match.198314.-"));
            long names = Allocations.perCall(500, 3, () -> sink = new MarketNames(naming, 1, Map.of(), event));
            long map = Allocations.perCall(500, 3, () -> sink = new ConcurrentHashMap<>());
            assertThat(names)
                    .as("bytes allocated by a market's names, against a map's %d", map)
                    .isLessThan(map);
        }
    }
}
