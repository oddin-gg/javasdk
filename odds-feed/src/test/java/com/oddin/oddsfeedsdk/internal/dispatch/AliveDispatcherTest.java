package com.oddin.oddsfeedsdk.internal.dispatch;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.internal.amqp.RawDelivery;
import com.oddin.oddsfeedsdk.internal.recovery.AliveFacts;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The alive dispatcher on its own thread: every alive of the SDK's channel reaches the recovery
 * actor, decoded, and measures its producer's clock offset; what is no alive breaks nothing.
 */
class AliveDispatcherTest {

    private static final long WAIT_SECONDS = 10;

    private final BlockingQueue<String> posted = new LinkedBlockingQueue<>();
    /** Producers 1 and 2, as the fixtures' list has them. */
    private final ClockOffsets offsets = new ClockOffsets(id -> id == 1 || id == 2);

    private final AliveFacts actor = (producer, generatedAt, receivedAt, subscribed) ->
            posted.add(producer + " " + generatedAt + " " + receivedAt + " " + subscribed + " "
                    + Thread.currentThread().getName());
    private @Nullable AliveDispatcher dispatcher;

    @AfterEach
    void close() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void anAliveReachesTheActorWithWhenTheSdkReceivedItAndMeasuresTheOffset() throws InterruptedException {
        AliveDispatcher dispatcher = started();
        dispatcher.accept(alive(FeedMessages.stampedAt(FeedMessages.alive(2, true), 10_000), 12_500));
        assertThat(next()).isEqualTo("2 10000 12500 true oddsfeed-alives");
        assertThat(offsets.age(2, 20_000, 22_500))
                .as("the producer's clock 2.5 s behind")
                .isZero();
        assertThat(offsets.age(1, 20_000, 22_500))
                .as("no alive of producer 1: no offset")
                .isEqualTo(2_500);

        dispatcher.accept(alive(FeedMessages.alive(1, false), 99));
        assertThat(next()).startsWith("1 1777832981632 99 false");
    }

    @Test
    void anAliveOfAProducerTheListDoesNotHaveKeepsNoOffset() throws InterruptedException {
        AliveDispatcher dispatcher = started();
        dispatcher.accept(alive(FeedMessages.stampedAt(FeedMessages.alive(7, true), 10_000), 12_500));
        assertThat(next()).as("the actor drops and counts it").startsWith("7 10000 12500 true");
        assertThat(offsets.age(7, 20_000, 22_500)).as("no offset").isEqualTo(2_500);
    }

    @Test
    void whatIsNoAliveIsCountedAndTheNextAliveStillArrives() throws InterruptedException {
        AliveDispatcher dispatcher = started();
        dispatcher.accept(alive("<alive", 1));
        dispatcher.accept(alive(Fixtures.read("feed/bet_stop/bet_stop_all_groups.xml"), 1));
        dispatcher.accept(new RawDelivery(null, 2 << 20, "-.-.-.alive.-.-.-.-", 0, 0, Instant.EPOCH, null));
        dispatcher.accept(alive(FeedMessages.alive(2, true), 5));
        assertThat(next()).startsWith("2 1777832981632 5 true");
        assertThat(dispatcher.unreadable()).isEqualTo(3);
        // counted once the actor has it, so after the test hears it
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (dispatcher.handled() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(dispatcher.handled()).isEqualTo(4);
    }

    @Test
    void anAliveWithoutAPositiveTimestampIsCountedAndReachesNeitherTheOffsetsNorTheActor() throws InterruptedException {
        AliveDispatcher dispatcher = started();
        dispatcher.accept(alive(FeedMessages.stampedAt(FeedMessages.alive(2, true), 10_000), 12_500));
        assertThat(next()).startsWith("2 10000 12500 true");

        dispatcher.accept(alive(FeedMessages.stampedAt(FeedMessages.alive(2, true), 0), 13_000));
        dispatcher.accept(alive(FeedMessages.stampedAt(FeedMessages.alive(2, false), -5_000), 14_000));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (dispatcher.handled() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(dispatcher.handled()).isEqualTo(3);
        assertThat(dispatcher.unreadable()).isEqualTo(2);
        assertThat(posted).as("posted to the actor").isEmpty();
        assertThat(offsets.age(2, 20_000, 22_500))
                .as("the offset of the alive with a time, 2.5 s")
                .isZero();
    }

    @Test
    void anActorThatThrowsDoesNotEndTheDispatcher() throws InterruptedException {
        var throwing = new AliveDispatcher(
                FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES),
                offsets,
                (producer, generatedAt, receivedAt, subscribed) -> {
                    if (producer == 1) {
                        throw new IllegalStateException("the actor's bug");
                    }
                    actor.alive(producer, generatedAt, receivedAt, subscribed);
                });
        dispatcher = throwing;
        throwing.start();
        throwing.accept(alive(FeedMessages.alive(1, true), 1));
        throwing.accept(alive(FeedMessages.alive(2, true), 2));
        assertThat(next()).startsWith("2 ");
    }

    @Test
    void alivesHandedOverBeforeTheStartArriveOnceItStarts() throws InterruptedException {
        var waiting = new AliveDispatcher(FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES), offsets, actor);
        dispatcher = waiting;
        waiting.accept(alive(FeedMessages.alive(2, true), 1));
        assertThat(waiting.queued()).isEqualTo(1);
        waiting.start();
        assertThat(next()).startsWith("2 ");
    }

    @Test
    void aFullQueueDropsTheAlivesWithNoRoomAndCountsThem() throws InterruptedException {
        var waiting = new AliveDispatcher(FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES), offsets, actor);
        dispatcher = waiting;
        for (int i = 0; i < AliveDispatcher.CAPACITY; i++) {
            waiting.accept(alive(FeedMessages.alive(2, true), i));
        }
        waiting.accept(alive(FeedMessages.alive(1, false), 1));
        assertThat(waiting.queued()).isEqualTo(AliveDispatcher.CAPACITY);
        assertThat(waiting.dropped()).isEqualTo(1);

        waiting.start();
        for (int i = 0; i < AliveDispatcher.CAPACITY; i++) {
            assertThat(next()).startsWith("2 ");
        }
        assertThat(waiting.queued()).as("the room given back").isZero();
        waiting.accept(alive(FeedMessages.alive(1, false), 2));
        assertThat(next()).as("room again").startsWith("1 ");
        assertThat(posted).isEmpty();
    }

    @Test
    void largeBodiesFillTheQueueByTheirBytes() throws InterruptedException {
        var waiting = new AliveDispatcher(FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES), offsets, actor);
        dispatcher = waiting;
        byte[] large = new byte[(int) (AliveDispatcher.BYTES / 2) + 1];
        waiting.accept(new RawDelivery(large, large.length, "-.-.-.alive.-.-.-.-", 0, 0, Instant.EPOCH, null));
        waiting.accept(new RawDelivery(large, large.length, "-.-.-.alive.-.-.-.-", 0, 0, Instant.EPOCH, null));
        assertThat(waiting.queued()).isEqualTo(1);
        assertThat(waiting.dropped()).isEqualTo(1);
        waiting.accept(alive(FeedMessages.alive(2, true), 1));
        assertThat(waiting.queued()).as("a small alive still fits").isEqualTo(2);

        waiting.start();
        assertThat(next()).startsWith("2 ");
        assertThat(waiting.unreadable()).isEqualTo(1);
    }

    @Test
    void theAlivesTheCloseDropsReadAsNoneQueued() {
        var waiting = new AliveDispatcher(FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES), offsets, actor);
        dispatcher = waiting;
        for (int i = 0; i < 3; i++) {
            waiting.accept(alive(FeedMessages.alive(2, true), i));
        }
        assertThat(waiting.queued()).isEqualTo(3);
        waiting.stop();
        assertThat(waiting.awaitStop(System.nanoTime())).isTrue();
        assertThat(waiting.queued())
                .as("what getHealth() reads once the feed is closed")
                .isZero();
        assertThat(posted).isEmpty();
    }

    @Test
    void anAliveHandedOverAsTheCloseDrainsTheQueueIsGivenBackToo() {
        var waiting = new AliveDispatcher(FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES), offsets, actor);
        dispatcher = waiting;
        // the close, stop and drain, between the hand-off's look at it and its add
        waiting.beforeQueued = () -> {
            waiting.stop();
            waiting.awaitStop(System.nanoTime());
        };
        waiting.accept(alive(FeedMessages.alive(2, true), 1));
        assertThat(waiting.queued()).as("queued once the close has drained").isZero();
        assertThat(posted).isEmpty();
    }

    private AliveDispatcher started() {
        var started = new AliveDispatcher(FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES), offsets, actor);
        dispatcher = started;
        started.start();
        return started;
    }

    private String next() throws InterruptedException {
        return requireNonNull(posted.poll(WAIT_SECONDS, TimeUnit.SECONDS), "an alive posted");
    }

    private static RawDelivery alive(String xml, long receivedAt) {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        return new RawDelivery(body, body.length, "-.-.-.alive.-.-.-.-", 0, 0, Instant.ofEpochMilli(receivedAt), null);
    }
}
