package gg.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resumes after a restart. Each producer's resume point, {@code getTimestampForRecovery()}, is saved
 * to a file while the feed runs and again after {@code close()}, and passed back to the next start,
 * which then recovers only what it missed instead of a full snapshot.
 *
 * <p>The file is {@code resume-points.properties} in the working directory, or the path in the
 * first argument. Run it, stop it, run it again, and look at the producers' first recovery in the
 * log.
 */
public final class ResumeAfterRestart {

    private static final Logger LOG = LoggerFactory.getLogger(ResumeAfterRestart.class);

    private ResumeAfterRestart() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        var file = Path.of(args.length > 0 ? args[0] : "resume-points.properties");
        var feed = new OddsFeed(new LoggingGlobalListener(), ExampleConfig.fromEnvironment());

        // The producers are known once the feed has started, and the hook and the finally cover that
        // start too. They save nothing while the list is empty, and it is filled only once every
        // producer has its resume point back: a start that failed before that leaves the file as it was.
        var all = new CopyOnWriteArrayList<Producer>();
        // The hook saves after close(): that is when the points are final.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeAndSave(feed, all, file), "close-feed"));
        try {
            var producers = feed.getProducerManager(); // this starts the feed, so it needs the API
            var available = producers.getAvailableProducers().values();
            var saved = load(file);
            for (var producer : available) {
                var id = producer.getId();
                long since = Long.parseLong(saved.getProperty(Long.toString(id), "0"));
                try {
                    // Before open(): after it the call is ignored. 0 means no resume point.
                    producers.setProducerRecoveryFromTimestamp(id, since);
                } catch (IllegalArgumentException tooOld) {
                    // Older than the producer's stateful recovery window: we were down too long.
                    LOG.warn("producer {}: {} is too old, asking for a full recovery", id, Instant.ofEpochMilli(since));
                    producers.setProducerRecoveryFromTimestamp(id, 0);
                }
            }
            all.addAll(available);

            feed.getSessionBuilder()
                    .setMessageInterest(MessageInterest.ALL)
                    .setListener(new LoggingListener())
                    .build();
            feed.open();

            while (true) {
                Thread.sleep(30_000);
                // A crash gives no shutdown to read the points in. An older point is safe: it only
                // makes the next recovery bigger.
                save(file, all);
            }
        } finally {
            closeAndSave(feed, all, file);
        }
    }

    private static void closeAndSave(OddsFeed feed, Collection<Producer> producers, Path file) {
        feed.close();
        if (!producers.isEmpty()) {
            save(file, producers);
        }
    }

    private static Properties load(Path file) throws IOException {
        var points = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                points.load(in);
            }
        }
        return points;
    }

    private static synchronized void save(Path file, Collection<Producer> producers) {
        var points = new Properties();
        for (var producer : producers) {
            // null: nothing to resume from yet, so leave the producer out and the next start asks for 0
            Instant point = producer.getTimestampForRecovery();
            if (point != null) {
                points.setProperty(Long.toString(producer.getId()), Long.toString(point.toEpochMilli()));
            }
        }
        try {
            // written beside the file and moved over it, so a crash leaves the old one whole
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                points.store(out, "resume points: producer id = epoch millis");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("could not save the resume points to {}", file, e);
        }
    }
}
