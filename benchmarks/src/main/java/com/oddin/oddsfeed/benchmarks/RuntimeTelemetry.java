package com.oddin.oddsfeed.benchmarks;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What a JVM spent while a load ran, sampled from inside it: the heap it allocated, the CPU it used
 * and its threads, every interval; the pauses come from the GC log ({@link GcLog}), which has them
 * to the microsecond. The numbers are the whole JVM's, so a run whose subject shares its JVM with a
 * generator measures both: the load harnesses run the SDK in a JVM of its own for this reason.
 */
public final class RuntimeTelemetry implements AutoCloseable {

    /** One sample. */
    public record Tick(double atSeconds, long allocatedBytes, long cpuNanos, int threads, long heapUsedBytes) {}

    private final ThreadMXBean threads = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
    private final OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    private final List<Tick> ticks = new CopyOnWriteArrayList<>();
    private final Thread sampler;
    private final long started = System.nanoTime();
    private final double uptimeAtStart = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;

    private RuntimeTelemetry(Duration interval) {
        tick();
        sampler = Thread.ofPlatform().daemon().name("telemetry-sampler").unstarted(() -> {
            try {
                while (true) {
                    Thread.sleep(interval);
                    tick();
                }
            } catch (InterruptedException closed) {
                // stopped
            }
        });
        sampler.start();
    }

    /** Starts sampling now, every {@code interval}. */
    public static RuntimeTelemetry start(Duration interval) {
        return new RuntimeTelemetry(interval);
    }

    /** The JVM's uptime when sampling began, in seconds: where a GC log's times line up with this run's. */
    public double uptimeAtStartSeconds() {
        return uptimeAtStart;
    }

    private void tick() {
        ticks.add(new Tick(
                (System.nanoTime() - started) / 1e9,
                threads.getTotalThreadAllocatedBytes(),
                os.getProcessCpuTime(),
                threads.getThreadCount(),
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
    }

    public List<Tick> ticks() {
        return List.copyOf(ticks);
    }

    @Override
    public void close() {
        sampler.interrupt();
        tick();
    }

    /** The samples as CSV: seconds, bytes allocated so far, process CPU seconds, threads, heap in use. */
    public String csv() {
        var out = new StringBuilder("seconds,allocated_bytes,cpu_seconds,threads,heap_used_bytes\n");
        for (var tick : ticks) {
            out.append(String.format(
                    Locale.ROOT,
                    "%.1f,%d,%.2f,%d,%d%n",
                    tick.atSeconds(),
                    tick.allocatedBytes(),
                    tick.cpuNanos() / 1e9,
                    tick.threads(),
                    tick.heapUsedBytes()));
        }
        return out.toString();
    }

    /** The live threads' names with their trailing numbers taken off, counted: where a leak would show. */
    public static Map<String, Integer> threadGroups() {
        var groups = new TreeMap<String, Integer>();
        for (var thread : Thread.getAllStackTraces().keySet()) {
            groups.merge(thread.getName().replaceAll("[-# _]*\\d+$", "").replaceAll("\\d+", "N"), 1, Integer::sum);
        }
        return groups;
    }

    /**
     * A summary of the run as text: whole-run figures and a row for each of {@code windows} equal
     * stretches of it, so that a trend (the heap after a GC creeping up, threads growing, the
     * allocation rate changing) shows.
     *
     * @param pauses the GC pauses of the run, with their time in seconds from the JVM's start
     * @param windows how many rows of the trend
     */
    public String summary(List<GcLog.Pause> pauses, int windows) {
        var samples = ticks();
        var first = samples.getFirst();
        var last = samples.getLast();
        double seconds = last.atSeconds() - first.atSeconds();
        var shifted = new ArrayList<GcLog.Pause>();
        for (var pause : pauses) {
            shifted.add(new GcLog.Pause(
                    pause.atSeconds() - uptimeAtStart,
                    pause.kind(),
                    pause.beforeBytes(),
                    pause.afterBytes(),
                    pause.millis()));
        }
        var out = new StringBuilder();
        double allocated = (last.allocatedBytes() - first.allocatedBytes()) / 1e6;
        out.append(String.format(
                Locale.ROOT,
                "ran %.0f s; allocated %.1f GB, %.1f MB/s; process CPU %.2f cores busy on average%n",
                seconds,
                allocated / 1000,
                allocated / seconds,
                (last.cpuNanos() - first.cpuNanos()) / 1e9 / seconds));
        var millis = shifted.stream().mapToDouble(GcLog.Pause::millis).toArray();
        double total = 0;
        for (var ms : millis) {
            total += ms;
        }
        out.append(String.format(
                Locale.ROOT,
                "GC pauses: %d, p50 %.2f ms, p99 %.2f ms, max %.2f ms, %.2f s in all (%.2f %% of the run)%n",
                millis.length,
                Stats.percentile(millis, 50),
                Stats.percentile(millis, 99),
                Stats.percentile(millis, 100),
                total / 1000,
                total / 10 / seconds));
        var kinds = new TreeMap<String, Integer>();
        shifted.forEach(pause -> kinds.merge(pause.kind(), 1, Integer::sum));
        out.append("pauses by kind: ").append(kinds).append("\n");
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (var tick : samples) {
            min = Math.min(min, tick.threads());
            max = Math.max(max, tick.threads());
        }
        out.append(String.format(
                Locale.ROOT,
                "threads: %d at the start, %d at the end, between %d and %d%n",
                first.threads(),
                last.threads(),
                min,
                max));
        out.append(String.format(
                Locale.ROOT,
                "%-14s %9s %6s %7s %9s %9s %16s %7s%n",
                "window",
                "alloc MB/s",
                "cores",
                "pauses",
                "p99 ms",
                "max ms",
                "heap after GC MB",
                "threads"));
        for (int w = 0; w < windows; w++) {
            double from = first.atSeconds() + seconds * w / windows;
            double to = first.atSeconds() + seconds * (w + 1) / windows;
            var inside = samples.stream()
                    .filter(tick -> tick.atSeconds() >= from && tick.atSeconds() <= to)
                    .toList();
            if (inside.size() < 2) {
                continue;
            }
            var a = inside.getFirst();
            var b = inside.getLast();
            double span = b.atSeconds() - a.atSeconds();
            var pausesIn = shifted.stream()
                    .filter(pause -> pause.atSeconds() >= from && pause.atSeconds() < to)
                    .toList();
            var ms = pausesIn.stream().mapToDouble(GcLog.Pause::millis).toArray();
            var after = pausesIn.stream().mapToLong(GcLog.Pause::afterBytes).toArray();
            out.append(String.format(
                    Locale.ROOT,
                    "%5.1f-%-5.1f min %9.1f %6.2f %7d %9.2f %9.2f %16s %7d%n",
                    from / 60,
                    to / 60,
                    (b.allocatedBytes() - a.allocatedBytes()) / 1e6 / span,
                    (b.cpuNanos() - a.cpuNanos()) / 1e9 / span,
                    ms.length,
                    Stats.percentile(ms, 99),
                    Stats.percentile(ms, 100),
                    after.length == 0
                            ? "-"
                            : Stats.percentile(after, 1) / (1 << 20) + " to "
                                    + Stats.percentile(after, 100) / (1 << 20),
                    b.threads()));
        }
        return out.toString();
    }
}
