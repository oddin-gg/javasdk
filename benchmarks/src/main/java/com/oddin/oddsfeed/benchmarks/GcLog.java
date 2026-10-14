package com.oddin.oddsfeed.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The pauses in a unified GC log ({@code -Xlog:gc:file=...:uptime}): what the JVM measured to the
 * microsecond, which the management beans only give in whole milliseconds. Concurrent phases are not
 * pauses and are left out.
 */
public final class GcLog {

    /** One stop-the-world pause. */
    public record Pause(double atSeconds, String kind, long beforeBytes, long afterBytes, double millis) {}

    private static final Pattern PAUSE = Pattern.compile("^\\[(\\d+(?:\\.\\d+)?)s\\]\\s+GC\\(\\d+\\)\\s+Pause (\\w+)"
            + ".*?\\s(\\d+)([KMG])->(\\d+)([KMG])\\(\\d+[KMG]\\)\\s+(\\d+(?:\\.\\d+)?)ms\\s*$");

    private GcLog() {}

    /** The pauses at or after {@code fromSeconds} of the JVM's uptime, in log order. */
    public static List<Pause> pauses(Path log, double fromSeconds) throws IOException {
        var pauses = new ArrayList<Pause>();
        for (var line : Files.readAllLines(log)) {
            var matcher = PAUSE.matcher(line);
            if (matcher.matches()) {
                double at = Double.parseDouble(matcher.group(1));
                if (at >= fromSeconds) {
                    pauses.add(new Pause(
                            at,
                            matcher.group(2),
                            bytes(matcher.group(3), matcher.group(4)),
                            bytes(matcher.group(5), matcher.group(6)),
                            Double.parseDouble(matcher.group(7))));
                }
            }
        }
        return pauses;
    }

    private static long bytes(String amount, String unit) {
        long scale = switch (unit) {
            case "K" -> 1L << 10;
            case "M" -> 1L << 20;
            default -> 1L << 30;
        };
        return Long.parseLong(amount) * scale;
    }
}
