package com.oddin.oddsfeed.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The warm path's steps do the work their budget is for: a step that did less would only look
 * faster. Run here, outside JMH, once per size the benchmark has.
 */
class WarmPathBenchmarkTest {

    @ParameterizedTest
    @ValueSource(ints = {Corpus.SMALL, Corpus.TYPICAL, Corpus.LARGE})
    void eachStepDoesItsWork(int markets) throws Exception {
        var benchmark = new WarmPathBenchmark();
        benchmark.markets = markets;
        benchmark.prepare();
        try {
            assertThat(benchmark.cacheWrite()).as("the first write").isTrue();
            assertThat(benchmark.cacheWrite())
                    .as("each a newer message, so each writes")
                    .isTrue();

            var sent = (OFOddsChange)
                    FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES).decode(Corpus.oddsChange(markets));
            var built = benchmark.entityBuild();
            assertThat(built).as("markets built").hasSize(markets);
            assertThat(built.stream().mapToInt(market -> market.getOutcomeOdds().size()))
                    .as("outcomes built")
                    .containsExactlyElementsOf(sent.getOdds().getMarket().stream()
                            .map(market -> market.getOutcome().size())
                            .toList());
        } finally {
            benchmark.close();
        }
    }
}
