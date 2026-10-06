package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runs the benchmarks briefly and holds the SDK's decoder, and the warm path's cache write and entity
 * build, to their budget per message, from {@code budgets.properties}: microseconds and bytes
 * allocated. The allocation is the tight check - it
 * hardly moves from one machine or run to the next - and the time a coarse one, generous enough for a
 * shared CI runner, there to catch a step that became several times slower. No time budget is under
 * {@link #TIME_FLOOR_US}: below it the runner's noise weighs as much as the code.
 *
 * <p>JMH forks a JVM of its own for the measurement, with its own options, so the coverage agent
 * the build gives the tests does not run inside it.
 *
 * <p>The build runs only the benchmarks with a budget. {@code -Dbenchmarks.include=DecodeBenchmark}
 * runs the comparison with the other decoders too, and {@code -Dbenchmarks.iterations=10} measures
 * longer.
 */
class BudgetTest {

    /** The least time budget, in microseconds. */
    private static final double TIME_FLOOR_US = 10;

    @Test
    void noTimeBudgetIsUnderTheFloor() throws IOException {
        Properties budgets = budgets();
        assertThat(budgets.stringPropertyNames())
                .filteredOn(key -> key.endsWith(".us"))
                .isNotEmpty()
                .allSatisfy(key -> assertThat(Double.parseDouble(budgets.getProperty(key)))
                        .as(key)
                        .isGreaterThanOrEqualTo(TIME_FLOOR_US));
    }

    @Test
    void theDecoderStaysWithinItsBudget() throws Exception {
        Properties budgets = budgets();
        // a run narrowed to some benchmarks, as for the comparison, holds only those to their budgets
        @Nullable String narrowed = System.getProperty("benchmarks.include");
        Collection<RunResult> results = new Runner(new OptionsBuilder()
                        .include(
                                narrowed != null
                                        ? narrowed
                                        : DecodeBenchmark.class.getName() + ".jaxb$|"
                                                + WarmPathBenchmark.class.getName())
                        .forks(1)
                        .warmupIterations(Integer.getInteger("benchmarks.warmups", 3))
                        .warmupTime(TimeValue.seconds(1))
                        .measurementIterations(Integer.getInteger("benchmarks.iterations", 3))
                        .measurementTime(TimeValue.seconds(1))
                        .addProfiler(GCProfiler.class)
                        .jvmArgs("-Xms1g", "-Xmx1g", "-XX:+UseParallelGC")
                        .build())
                .run();

        var overBudget = new ArrayList<String>();
        var report = new ArrayList<String>();
        for (RunResult result : results) {
            String name = result.getParams().getBenchmark().replaceAll(".*\\.", "") + "."
                    + result.getParams().getParam("markets");
            double micros = result.getPrimaryResult().getScore();
            double bytes = allocation(result);
            report.add("%-12s %10.1f us %12.0f B".formatted(name, micros, bytes));
            String time = budgets.getProperty(name + ".us");
            String allocated = budgets.getProperty(name + ".bytes");
            if (time != null && micros > Double.parseDouble(time)) {
                overBudget.add("%s took %.1f us, over its budget of %s us".formatted(name, micros, time));
            }
            if (allocated != null && bytes > Double.parseDouble(allocated)) {
                overBudget.add("%s allocated %.0f B, over its budget of %s B".formatted(name, bytes, allocated));
            }
        }
        System.out.println("per odds change, warm:\n  " + String.join("\n  ", report));
        var measured = new ArrayList<String>();
        results.forEach(result -> measured.add(result.getParams().getBenchmark().replaceAll(".*\\.", "") + "."
                + result.getParams().getParam("markets")));
        // in the build's run, a budget whose benchmark did not run would pass without a word
        if (narrowed == null) {
            assertThat(budgets.stringPropertyNames())
                    .as("budgeted benchmarks")
                    .isNotEmpty()
                    .allSatisfy(key -> assertThat(measured).contains(key.substring(0, key.lastIndexOf('.'))));
        } else {
            assertThat(results).as("benchmarks matching " + narrowed).isNotEmpty();
        }
        assertThat(overBudget).as("benchmarks over budget").isEmpty();
    }

    /** Bytes allocated per operation, as the GC profiler reports it. */
    private static double allocation(RunResult result) {
        // JMH hands the map out with raw Result values
        Map<String, ?> secondary = result.getSecondaryResults();
        return secondary.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("gc.alloc.rate.norm"))
                .mapToDouble(entry -> ((Result<?>) entry.getValue()).getScore())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no allocation result among " + secondary.keySet()));
    }

    private static Properties budgets() throws IOException {
        var budgets = new Properties();
        try (InputStream in = requireNonNull(BudgetTest.class.getResourceAsStream("/budgets.properties"))) {
            budgets.load(in);
        }
        return budgets;
    }
}
