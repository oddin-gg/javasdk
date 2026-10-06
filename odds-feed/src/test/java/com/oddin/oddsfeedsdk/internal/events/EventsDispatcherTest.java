package com.oddin.oddsfeedsdk.internal.events;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.recovery.ProducerStatusChange;
import com.oddin.oddsfeedsdk.internal.recovery.StatusCause;
import com.oddin.oddsfeedsdk.internal.rest.ApiCall;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ApiCallEvent;
import com.oddin.oddsfeedsdk.subscribe.CallbackFailure;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The events dispatcher on its own thread: every event reaches its callback there, a callback that
 * throws breaks nothing, and a client that falls behind costs a bounded queue - with the newest
 * producer status and connection state never the ones dropped.
 */
class EventsDispatcherTest {

    private static final long WAIT_SECONDS = 10;
    private static final long PRODUCER = 1;
    private static final URI URI_CALLED = URI.create("https://api.example.invalid/v1/users/whoami");

    private final Listener listener = new Listener();
    private final Producers producers = producers();
    private @Nullable EventsDispatcher dispatcher;

    @AfterEach
    void close() {
        listener.release();
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void aProducerStatusReachesItsCallbackOnTheEventsThread() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        dispatcher.producerStatus(
                new ProducerStatusChange(PRODUCER, false, true, StatusCause.FIRST_RECOVERY_COMPLETED, 1_000));

        assertThat(listener.next()).isEqualTo("onProducerStatusChange oddsfeed-events");
        ProducerStatus status = requireNonNull(listener.statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(requireNonNull(status.getProducer()).getId()).isEqualTo(PRODUCER);
        assertThat(status.isDown()).isFalse();
        assertThat(status.isDelayed()).isTrue();
        assertThat(status.getProducerStatusReason()).isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);
        assertThat(status.getTimestamp()).isEqualTo(new MessageTimestamp(1_000, 1_000, 1_000, 1_000));
    }

    @Test
    void aProducerTheListDoesNotHaveIsReportedWithoutOne() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        dispatcher.producerStatus(new ProducerStatusChange(99, true, false, StatusCause.CONNECTION_LOST, 1));
        ProducerStatus status = requireNonNull(listener.statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(status.getProducer()).isNull();
        assertThat(status.getProducerStatusReason()).isEqualTo(ProducerStatusReason.OTHER);
    }

    @Test
    void anEventRecoveryCompletionReachesItsCallback() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 7);
        assertThat(listener.next()).isEqualTo("onEventRecoveryCompleted od:match:1 7");
    }

    @Test
    void theConnectionsStatesReachTheirCallbackAndALossTheLegacyOneFirst() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        dispatcher.connecting();
        assertThat(listener.next()).isEqualTo("onConnectionStateChange CONNECTING null 0 PT0S");
        dispatcher.up();
        assertThat(listener.next()).isEqualTo("onConnectionStateChange UP null 0 PT0S");
        dispatcher.down("the broker went away");
        assertThat(listener.next()).isEqualTo("onConnectionDown");
        assertThat(listener.next()).isEqualTo("onConnectionStateChange DOWN the broker went away 0 PT0S");
        dispatcher.recovering(2, 1_500, "connection refused");
        assertThat(listener.next()).isEqualTo("onConnectionStateChange RECOVERING connection refused 2 PT1.5S");
    }

    @Test
    void aFatalErrorFromTheBrokerOrTheApiReachesItsCallback() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        var cause = new IllegalStateException("ACCESS_REFUSED");
        dispatcher.fatal("the broker refused the login", cause);
        assertThat(listener.next()).isEqualTo("onFatalError the broker refused the login ACCESS_REFUSED");

        dispatcher.refused(call(401, new IOException("401")));
        assertThat(listener.next())
                .isEqualTo("onFatalError The API refused the access token: GET " + URI_CALLED + " answered 401 401");
    }

    @Test
    void anApiCallReachesItsCallbackAsItWasMade() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        var failure = new IOException("reset");
        dispatcher.called(call(503, failure));
        assertThat(listener.next()).isEqualTo("onApiCall");
        ApiCallEvent event = requireNonNull(listener.calls.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(event.method()).isEqualTo("GET");
        assertThat(event.uri()).isEqualTo(URI_CALLED);
        assertThat(event.status()).isEqualTo(503);
        assertThat(event.latency()).isEqualTo(Duration.ofMillis(12));
        assertThat(event.attempt()).isEqualTo(2);
        assertThat(event.failure()).isSameAs(failure);
        assertThat(event.at()).isNotNull();
    }

    @Test
    void theRawApiDataReachesBothRawCallbacksOfTheExtendedListener() throws InterruptedException {
        var ext = new Ext();
        EventsDispatcher dispatcher = started(ext);
        Object decoded = new Object();
        dispatcher.received(URI_CALLED, decoded, new byte[] {'<', 'x', '/', '>'});
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                .isEqualTo("onRawApiDataReceived " + URI_CALLED + " " + decoded + " oddsfeed-events");
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                .isEqualTo("onRawApiDataBytes " + URI_CALLED + " <x/> oddsfeed-events");
    }

    @Test
    void withoutAnExtendedListenerTheRawApiDataIsNotQueued() throws InterruptedException {
        // room for one telemetry event: a second one queued would drop the first
        EventsDispatcher dispatcher = dispatcher(null, 10, 1, EventsDispatcher.TELEMETRY_BYTES);
        dispatcher.start();
        listener.wedge();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 1);
        listener.awaitWedged();
        for (int i = 0; i < 5; i++) {
            dispatcher.received(URI_CALLED, new Object(), new byte[0]);
        }
        assertThat(dispatcher.telemetryDropped()).isZero();
        assertThat(dispatcher.controlDropped()).isZero();
        assertThat(dispatcher.rawDataDropped()).isZero();
    }

    /**
     * The design review's case: large API responses behind a wedged callback. The raw data the
     * telemetry queue holds stays within its budget of bytes; what does not fit is dropped and
     * counted, and the other telemetry is not.
     */
    @Test
    void rawApiDataBehindAWedgedCallbackStaysWithinItsBudgetOfBytes() throws InterruptedException {
        var ext = new Ext();
        EventsDispatcher dispatcher = dispatcher(ext, 10, 10, 100);
        dispatcher.start();
        listener.wedge();
        dispatcher.up();
        listener.awaitWedged();
        for (int i = 0; i < 3; i++) {
            dispatcher.received(URI_CALLED, "response " + i, new byte[60]);
        }
        dispatcher.called(call(200, null));
        dispatcher.received(URI_CALLED, "too large alone", new byte[101]);
        assertThat(dispatcher.rawDataDropped())
                .as("the two that did not fit, and the one too large")
                .isEqualTo(3);
        assertThat(dispatcher.telemetryDropped()).isZero();

        listener.release();
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                .startsWith("onRawApiDataReceived " + URI_CALLED + " response 0");
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS)).startsWith("onRawApiDataBytes");
        assertThat(listener.take(2)).contains("onApiCall");
        assertThat(ext.events.poll(200, TimeUnit.MILLISECONDS))
                .as("nothing more queued")
                .isNull();

        dispatcher.received(URI_CALLED, "after", new byte[100]);
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the delivered one's bytes are free again")
                .startsWith("onRawApiDataReceived " + URI_CALLED + " after");
    }

    @Test
    void rawApiDataDroppedAsTheOldestFreesItsBytes() throws InterruptedException {
        var ext = new Ext();
        EventsDispatcher dispatcher = dispatcher(ext, 10, 2, 100);
        dispatcher.start();
        listener.wedge();
        dispatcher.up();
        listener.awaitWedged();
        dispatcher.received(URI_CALLED, "old", new byte[90]);
        dispatcher.called(call(200, null));
        dispatcher.called(call(201, null));
        assertThat(dispatcher.telemetryDropped()).as("the raw data, oldest").isEqualTo(1);
        dispatcher.received(URI_CALLED, "new", new byte[90]);
        assertThat(dispatcher.rawDataDropped()).as("room again for the new one").isZero();
    }

    @Test
    void aSessionsFailureReachesTheFailureCallback() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        var session = new OddsFeedSession() {};
        var thrown = new IllegalArgumentException("no market");
        dispatcher.callbackFailed("build", false, thrown, session);
        assertThat(listener.next()).isEqualTo("onCallbackFailure build false no market");
        CallbackFailure failure = requireNonNull(listener.failures.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(failure.session()).isSameAs(session);
        assertThat(failure.exception()).isSameAs(thrown);
    }

    @Test
    void aCallbackThatThrowsIsReportedAndTheNextEventStillArrives() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.throwOn = "onEventRecoveryCompleted";
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 1);
        assertThat(listener.next()).isEqualTo("onEventRecoveryCompleted od:match:1 1");
        assertThat(listener.next()).isEqualTo("onCallbackFailure onEventRecoveryCompleted true thrown by the client");
        dispatcher.up();
        assertThat(listener.next()).isEqualTo("onConnectionStateChange UP null 0 PT0S");
        assertThat(dispatcher.callbackFailures()).isEqualTo(1);
    }

    @Test
    void aFailureCallbackThatThrowsIsNotReportedAgain() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.throwOn = "onCallbackFailure";
        dispatcher.callbackFailed("build", false, new IllegalStateException("x"), null);
        assertThat(listener.next()).isEqualTo("onCallbackFailure build false x");
        dispatcher.up();
        assertThat(listener.next())
                .as("no second report of the failure callback's own exception")
                .isEqualTo("onConnectionStateChange UP null 0 PT0S");
        assertThat(dispatcher.callbackFailures()).isEqualTo(1);
    }

    @Test
    void anErrorFromACallbackDoesNotEndTheThread() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.errorOn = "onConnectionStateChange";
        dispatcher.up();
        assertThat(listener.next()).isEqualTo("onConnectionStateChange UP null 0 PT0S");
        assertThat(listener.next()).startsWith("onCallbackFailure onConnectionStateChange true");
        listener.errorOn = "";
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:2"), 2);
        assertThat(listener.next()).isEqualTo("onEventRecoveryCompleted od:match:2 2");
    }

    @Test
    void anInterruptACallbackLeavesIsCleared() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.interruptOn = "onEventRecoveryCompleted";
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 1);
        assertThat(listener.next()).isEqualTo("onEventRecoveryCompleted od:match:1 1");
        dispatcher.up();
        assertThat(listener.next()).isEqualTo("onConnectionStateChange UP null 0 PT0S");
        assertThat(listener.interruptedAfter)
                .as("the thread's interrupt in the next callback")
                .isFalse();
    }

    @Test
    void whileACallbackRunsTheWatchdogSeesSince() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        assertThat(dispatcher.busySince()).isZero();
        listener.wedge();
        dispatcher.up();
        listener.awaitWedged();
        assertThat(dispatcher.busySince()).isPositive();
        listener.release();
        assertThat(listener.next()).startsWith("onConnectionStateChange UP");
        awaitIdle(dispatcher);
        assertThat(dispatcher.busySince()).isZero();
    }

    /**
     * The design review's case: a wedged status callback, the control queue filling up behind it. The
     * newest status of the producer still reaches the client once it catches up, in place of the ones
     * it replaced, and so does the connection's state, with its loss told.
     */
    @Test
    void aFullControlQueueDropsNeitherTheNewestProducerStatusNorTheConnectionsState() throws InterruptedException {
        EventsDispatcher dispatcher = dispatcher(null, 3, 10);
        dispatcher.start();
        listener.wedge();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:0"), 0);
        listener.awaitWedged();

        dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, true, false, StatusCause.CONNECTION_LOST, 1));
        dispatcher.down("lost");
        for (int i = 1; i <= 5; i++) {
            dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:" + i), i);
        }
        dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, false, false, StatusCause.RECOVERY_COMPLETED, 2));
        dispatcher.connecting();
        dispatcher.up();
        assertThat(dispatcher.controlDropped())
                .as("completions beyond the queue's three")
                .isEqualTo(2);

        listener.release();
        List<String> heard = listener.take(7);
        assertThat(heard)
                .containsExactly(
                        "onEventRecoveryCompleted od:match:0 0",
                        "onEventRecoveryCompleted od:match:1 1",
                        "onEventRecoveryCompleted od:match:2 2",
                        "onEventRecoveryCompleted od:match:3 3",
                        "onProducerStatusChange " + "oddsfeed-events",
                        "onConnectionDown",
                        "onConnectionStateChange UP null 0 PT0S");
        ProducerStatus status = requireNonNull(listener.statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(status.isDown()).as("the newest status, up again").isFalse();
        assertThat(status.getProducerStatusReason()).isEqualTo(ProducerStatusReason.RETURNED_FROM_INACTIVITY);
        assertThat(listener.statuses).as("the replaced status").isEmpty();
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    /**
     * The second design review's case: behind a wedge, a producer goes down, the connection is lost,
     * the producer is down for the loss, the connection comes back and the producer up. Each slot's
     * newest is heard where it was reported, so the client hears the connection lost before the
     * producer up - a client that takes every producer down on a lost connection is left with it up.
     */
    @Test
    void aSlotsNewestIsHeardWhereItWasReported() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:0"), 0);
        listener.awaitWedged();
        dispatcher.producerStatus(
                new ProducerStatusChange(PRODUCER, true, false, StatusCause.ALIVE_INTERVAL_VIOLATION, 1));
        dispatcher.down("lost");
        dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, true, false, StatusCause.CONNECTION_LOST, 2));
        dispatcher.recovering(1, 100, "refused");
        dispatcher.up();
        dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, false, false, StatusCause.RECOVERY_COMPLETED, 3));

        listener.release();
        assertThat(listener.take(4))
                .containsExactly(
                        "onEventRecoveryCompleted od:match:0 0",
                        "onConnectionDown",
                        "onConnectionStateChange UP null 0 PT0S",
                        "onProducerStatusChange oddsfeed-events");
        ProducerStatus status = requireNonNull(listener.statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(status.isDown()).as("up, last").isFalse();
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("nothing more")
                .isNull();
    }

    /** A slot keeps one marker however often it is filled, so a flood costs one entry. */
    @Test
    void aFloodOfReportsForOneSlotKeepsOneEntryQueued() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:0"), 0);
        listener.awaitWedged();
        for (int i = 0; i < 10_000; i++) {
            dispatcher.producerStatus(
                    new ProducerStatusChange(PRODUCER, i % 2 == 0, false, StatusCause.UNSUBSCRIBED, i));
            dispatcher.connecting();
        }
        assertThat(dispatcher.controlQueued()).as("one marker per slot").isEqualTo(2);
        listener.release();
        assertThat(listener.take(3)).hasSize(3);
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("nothing more")
                .isNull();
    }

    @Test
    void aConnectionLossWhoseLegacyCallbackThrowsStillTellsTheState() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.throwOn = "onConnectionDown";
        dispatcher.down("lost");
        assertThat(listener.take(3))
                .containsExactly(
                        "onConnectionDown",
                        "onConnectionStateChange DOWN lost 0 PT0S",
                        "onCallbackFailure onConnectionDown true thrown by the client");
    }

    @Test
    void rawApiDataWhoseFirstCallbackThrowsStillGetsItsBytes() throws InterruptedException {
        var ext = new Ext();
        ext.throwOnReceived = true;
        EventsDispatcher dispatcher = started(ext);
        dispatcher.received(URI_CALLED, "data", new byte[] {'<', 'x', '/', '>'});
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS)).startsWith("onRawApiDataReceived");
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                .startsWith("onRawApiDataBytes " + URI_CALLED + " <x/>");
        assertThat(listener.next()).startsWith("onCallbackFailure onRawApiDataReceived true");
    }

    @Test
    void aCloseFromTheFirstCallbackOfAnEventLeavesTheSecondUnheard() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.onDown = dispatcher::close;
        dispatcher.down("lost");
        assertThat(listener.next()).isEqualTo("onConnectionDown");
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("the state, after the close")
                .isNull();
    }

    /** The response whose callbacks run counts against the budget until both are done. */
    @Test
    void rawApiDataBeingDeliveredStillCountsAgainstTheBudget() throws InterruptedException {
        var ext = new Ext();
        var received = new CountDownLatch(1);
        var bytes = new CountDownLatch(1);
        ext.wedged = received;
        ext.wedgedBytes = bytes;
        EventsDispatcher dispatcher = dispatcher(ext, 10, 10, 100);
        dispatcher.start();
        dispatcher.received(URI_CALLED, "first", new byte[60]);
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                .startsWith("onRawApiDataReceived " + URI_CALLED + " first");
        dispatcher.received(URI_CALLED, "second", new byte[60]);
        assertThat(dispatcher.rawDataDropped())
                .as("the first's first callback runs")
                .isEqualTo(1);

        received.countDown();
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS)).startsWith("onRawApiDataBytes");
        dispatcher.received(URI_CALLED, "third", new byte[60]);
        assertThat(dispatcher.rawDataDropped())
                .as("the first's second callback runs")
                .isEqualTo(2);

        // heard once the first's callbacks are done and its bytes released
        dispatcher.called(call(200, null));
        bytes.countDown();
        assertThat(listener.next()).isEqualTo("onApiCall");
        dispatcher.received(URI_CALLED, "fourth", new byte[60]);
        assertThat(dispatcher.rawDataDropped()).as("both callbacks done").isEqualTo(2);
        assertThat(ext.events.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                .startsWith("onRawApiDataReceived " + URI_CALLED + " fourth");
    }

    /**
     * Two reports for one slot at once, the first held after it fills the slot and before it queues
     * its marker: the second waits for it, then takes that marker out rather than leaving it queued.
     * However often that happens, the slot keeps one entry, and its newest is heard once.
     */
    @Test
    void reportsForOneSlotAtOnceKeepOneEntryQueued() throws InterruptedException {
        // not started: every entry stays queued
        EventsDispatcher dispatcher = dispatcher(null, 10, 10);
        for (int round = 0; round < 5; round++) {
            var filled = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            holdTheFirstFill(dispatcher, filled, resume);
            var first = Thread.ofPlatform().start(() -> dispatcher.refused(call(401, null)));
            assertThat(filled.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the first report filled its slot")
                    .isTrue();
            var second = Thread.ofPlatform().start(() -> dispatcher.refused(call(403, null)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            // until the second is done, as it is when nothing holds it behind the first, or waits
            while (second.isAlive() && second.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            resume.countDown();
            first.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            second.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        assertThat(dispatcher.controlQueued()).as("one marker for the slot").isEqualTo(1);

        dispatcher.start();
        assertThat(listener.next())
                .isEqualTo("onFatalError The API refused the access token: GET " + URI_CALLED + " answered 403 null");
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("nothing more")
                .isNull();
    }

    /**
     * A marker the events thread takes while a newer report fills its slot is skipped: the newer one
     * is heard where its own marker goes, after what was reported before it.
     */
    @Test
    void aMarkerTakenWhileItsSlotIsFilledAgainIsSkipped() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:0"), 0);
        listener.awaitWedged();
        dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, true, false, StatusCause.CONNECTION_LOST, 1));
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 1);
        var filled = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        holdTheFirstFill(dispatcher, filled, resume);
        var newer = Thread.ofPlatform()
                .start(() -> dispatcher.producerStatus(
                        new ProducerStatusChange(PRODUCER, false, false, StatusCause.RECOVERY_COMPLETED, 2)));
        assertThat(filled.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the newer report filled the slot")
                .isTrue();

        listener.release();
        assertThat(listener.take(2))
                .as("the older marker skipped")
                .containsExactly("onEventRecoveryCompleted od:match:0 0", "onEventRecoveryCompleted od:match:1 1");
        resume.countDown();
        newer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(listener.next()).isEqualTo("onProducerStatusChange oddsfeed-events");
        ProducerStatus status = requireNonNull(listener.statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(status.isDown()).as("the newer status").isFalse();
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("nothing more")
                .isNull();
    }

    @Test
    void aFullTelemetryQueueDropsTheOldest() throws InterruptedException {
        EventsDispatcher dispatcher = dispatcher(null, 10, 3);
        dispatcher.start();
        listener.wedge();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:0"), 0);
        listener.awaitWedged();
        for (int status = 1; status <= 5; status++) {
            dispatcher.called(call(status, null));
        }
        assertThat(dispatcher.telemetryDropped()).isEqualTo(2);

        listener.release();
        assertThat(listener.take(4))
                .containsExactly("onEventRecoveryCompleted od:match:0 0", "onApiCall", "onApiCall", "onApiCall");
        var statuses = new ArrayList<Integer>();
        for (int i = 0; i < 3; i++) {
            statuses.add(requireNonNull(listener.calls.poll(WAIT_SECONDS, TimeUnit.SECONDS))
                    .status());
        }
        assertThat(statuses).as("the newest three").containsExactly(3, 4, 5);
    }

    @Test
    void controlEventsGoBeforeTelemetryQueuedEarlier() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:0"), 0);
        listener.awaitWedged();
        dispatcher.called(call(200, null));
        dispatcher.up();

        listener.release();
        assertThat(listener.take(3))
                .containsExactly(
                        "onEventRecoveryCompleted od:match:0 0", "onConnectionStateChange UP null 0 PT0S", "onApiCall");
    }

    @Test
    void aControlEventReportedWhileTelemetryIsDeliveredGoesBeforeTheRestOfIt() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.called(call(1, null));
        listener.awaitWedged();
        dispatcher.called(call(2, null));
        dispatcher.up();

        listener.release();
        assertThat(listener.take(3))
                .containsExactly("onApiCall", "onConnectionStateChange UP null 0 PT0S", "onApiCall");
    }

    @Test
    void reportingNeverWaitsForAWedgedClient() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.up();
        listener.awaitWedged();
        long started = System.nanoTime();
        for (int i = 0; i < 3 * EventsDispatcher.CONTROL_CAPACITY; i++) {
            dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), i);
            dispatcher.called(call(200, null));
            dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, true, false, StatusCause.UNSUBSCRIBED, i));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(WAIT_SECONDS));
        assertThat(dispatcher.controlDropped()).isEqualTo(2L * EventsDispatcher.CONTROL_CAPACITY);
        assertThat(dispatcher.telemetryDropped())
                .isEqualTo(3L * EventsDispatcher.CONTROL_CAPACITY - EventsDispatcher.TELEMETRY_CAPACITY);
    }

    @Test
    void eventsReportedBeforeTheStartArriveOnceItStarts() throws InterruptedException {
        EventsDispatcher dispatcher = dispatcher(null, 10, 10);
        dispatcher.connecting();
        dispatcher.up();
        assertThat(listener.events.poll(100, TimeUnit.MILLISECONDS)).isNull();
        dispatcher.start();
        assertThat(listener.next()).isEqualTo("onConnectionStateChange UP null 0 PT0S");
    }

    /**
     * A callback that closes the feed, as onFatalError says a client does: the close runs on the
     * events thread, which cannot wait for itself, so it returns at once rather than after the
     * close's wait, and nothing queued behind the callback is delivered.
     */
    @Test
    void aCallbackThatClosesTheDispatcherIsNotHeldUpByItsOwnThread() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        var closedIn = new java.util.concurrent.atomic.AtomicLong(-1);
        listener.onFatal = () -> {
            long started = System.nanoTime();
            dispatcher.close();
            closedIn.set(System.nanoTime() - started);
        };
        listener.wedge();
        dispatcher.up();
        listener.awaitWedged();
        dispatcher.fatal("refused", null);
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 1);
        listener.release();
        assertThat(listener.take(2))
                .containsExactly("onConnectionStateChange UP null 0 PT0S", "onFatalError refused null");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (closedIn.get() < 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(Duration.ofNanos(closedIn.get()))
                .as("close() from the callback")
                .isLessThan(Duration.ofSeconds(1));
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("after the close")
                .isNull();
    }

    /**
     * Each producer and each source of fatal errors has a slot of its own: behind a wedge, one
     * producer's status does not replace another's, nor the API's fatal error the broker's.
     */
    @Test
    void eachProducerAndEachSourceOfFatalErrorsHasItsOwnSlot() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.up();
        listener.awaitWedged();
        dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, true, false, StatusCause.CONNECTION_LOST, 1));
        dispatcher.producerStatus(new ProducerStatusChange(99, true, false, StatusCause.UNSUBSCRIBED, 2));
        dispatcher.producerStatus(new ProducerStatusChange(PRODUCER, false, false, StatusCause.RECOVERY_COMPLETED, 3));
        dispatcher.producerStatus(new ProducerStatusChange(99, true, false, StatusCause.ALIVE_INTERVAL_VIOLATION, 4));
        dispatcher.fatal("first broker refusal", null);
        dispatcher.refused(call(401, null));
        dispatcher.fatal("second broker refusal", null);
        dispatcher.refused(call(403, null));

        listener.release();
        assertThat(listener.take(5))
                .containsExactly(
                        "onConnectionStateChange UP null 0 PT0S",
                        "onProducerStatusChange oddsfeed-events",
                        "onProducerStatusChange oddsfeed-events",
                        "onFatalError second broker refusal null",
                        "onFatalError The API refused the access token: GET " + URI_CALLED + " answered 403 null");
        var first = requireNonNull(listener.statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        var second = requireNonNull(listener.statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(first.getTimestamp().getCreated()).as("producer 1's newest").isEqualTo(3);
        assertThat(second.getTimestamp().getCreated())
                .as("producer 99's newest")
                .isEqualTo(4);
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("nothing more")
                .isNull();
    }

    /**
     * A close while a callback is wedged, with events queued behind it: once the callback returns,
     * none of them is delivered.
     */
    @Test
    void eventsQueuedBehindAWedgedCallbackAreNotDeliveredAfterTheClose() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        listener.wedge();
        dispatcher.up();
        listener.awaitWedged();
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 1);
        dispatcher.down("lost");
        dispatcher.called(call(200, null));
        var closing = Thread.ofPlatform().start(dispatcher::close);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (closing.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        listener.release();
        closing.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(closing.isAlive()).as("close() returned").isFalse();
        assertThat(listener.take(1)).containsExactly("onConnectionStateChange UP null 0 PT0S");
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS))
                .as("after the wedged callback")
                .isNull();
    }

    @Test
    void afterTheCloseNothingIsDelivered() throws InterruptedException {
        EventsDispatcher dispatcher = started(null);
        dispatcher.up();
        assertThat(listener.next()).startsWith("onConnectionStateChange UP");
        dispatcher.close();
        dispatcher.down("late");
        dispatcher.eventRecoveryCompleted(PRODUCER, URN.parse("od:match:1"), 1);
        dispatcher.called(call(200, null));
        assertThat(listener.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    private EventsDispatcher started(@Nullable OddsFeedExtListener ext) {
        EventsDispatcher started = dispatcher(
                ext,
                EventsDispatcher.CONTROL_CAPACITY,
                EventsDispatcher.TELEMETRY_CAPACITY,
                EventsDispatcher.TELEMETRY_BYTES);
        started.start();
        return started;
    }

    private EventsDispatcher dispatcher(@Nullable OddsFeedExtListener ext, int control, int telemetry) {
        return dispatcher(ext, control, telemetry, EventsDispatcher.TELEMETRY_BYTES);
    }

    private EventsDispatcher dispatcher(@Nullable OddsFeedExtListener ext, int control, int telemetry, long bytes) {
        var made = new EventsDispatcher(listener, ext, producers, InstantSource.system(), control, telemetry, bytes);
        dispatcher = made;
        return made;
    }

    /** Holds the next report that fills a slot until {@code resume}, once {@code filled} is told. */
    private static void holdTheFirstFill(EventsDispatcher dispatcher, CountDownLatch filled, CountDownLatch resume) {
        var hold = new AtomicBoolean(true);
        dispatcher.afterFill = () -> {
            if (hold.getAndSet(false)) {
                filled.countDown();
                try {
                    resume.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    private static void awaitIdle(EventsDispatcher dispatcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (dispatcher.busySince() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static ApiCall call(int status, @Nullable Exception failure) {
        return new ApiCall("GET", URI_CALLED, status, Duration.ofMillis(12), 2, failure);
    }

    private static Producers producers() {
        var list = new RAProducers();
        var producer = new RAProducer();
        producer.setId(PRODUCER);
        producer.setName("pre");
        producer.setDescription("pre feed");
        producer.setApiUrl("https://api.example.invalid/v1/pre");
        producer.setActive(true);
        producer.setScope("prematch");
        producer.setStatefulRecoveryWindowInMinutes(4320);
        list.getProducer().add(producer);
        return new Producers(list);
    }

    /** Records each callback with what it got; can be wedged in its next callback, and made to throw. */
    private static final class Listener implements GlobalEventsListener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final BlockingQueue<ProducerStatus> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<ApiCallEvent> calls = new LinkedBlockingQueue<>();
        final BlockingQueue<CallbackFailure> failures = new LinkedBlockingQueue<>();
        volatile String throwOn = "";
        volatile String errorOn = "";
        volatile String interruptOn = "";
        volatile Runnable onFatal = () -> {};
        volatile Runnable onDown = () -> {};
        volatile boolean interruptedAfter;
        private volatile @Nullable CountDownLatch wedged;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void wedge() {
            wedged = released;
        }

        void awaitWedged() throws InterruptedException {
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("a callback wedged")
                    .isTrue();
        }

        void release() {
            released.countDown();
        }

        String next() throws InterruptedException {
            String event = events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(event).as("the next callback").isNotNull();
            return requireNonNull(event);
        }

        List<String> take(int count) throws InterruptedException {
            var taken = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                taken.add(next());
            }
            return taken;
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {
            statuses.add(producerStatus);
            heard(
                    "onProducerStatusChange",
                    "onProducerStatusChange " + Thread.currentThread().getName());
        }

        @Override
        public void onConnectionDown() {
            heard("onConnectionDown", "onConnectionDown");
            onDown.run();
        }

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {
            heard("onEventRecoveryCompleted", "onEventRecoveryCompleted " + eventId + " " + requestId);
        }

        @Override
        public void onConnectionStateChange(ConnectionStateChange change) {
            heard(
                    "onConnectionStateChange",
                    "onConnectionStateChange " + change.state() + " " + change.reason() + " " + change.attempt() + " "
                            + change.retryIn());
        }

        @Override
        public void onFatalError(String reason, @Nullable Throwable cause) {
            heard("onFatalError", "onFatalError " + reason + " " + (cause == null ? null : cause.getMessage()));
            onFatal.run();
        }

        @Override
        public void onApiCall(ApiCallEvent call) {
            calls.add(call);
            heard("onApiCall", "onApiCall");
        }

        @Override
        public void onCallbackFailure(CallbackFailure failure) {
            failures.add(failure);
            heard(
                    "onCallbackFailure",
                    "onCallbackFailure " + failure.callback() + " " + failure.clientCode() + " "
                            + failure.exception().getMessage());
        }

        private void heard(String callback, String event) {
            interruptedAfter |= Thread.currentThread().isInterrupted();
            CountDownLatch wedge = wedged;
            if (wedge != null) {
                wedged = null;
                entered.countDown();
                try {
                    wedge.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            events.add(event);
            if (callback.equals(throwOn)) {
                throw new IllegalStateException("thrown by the client");
            }
            if (callback.equals(errorOn)) {
                throw new AssertionError("an error of the client's");
            }
            if (callback.equals(interruptOn)) {
                interruptOn = "";
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Records the raw callbacks, with the thread each ran on. */
    private static final class Ext implements OddsFeedExtListener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        volatile boolean throwOnReceived;
        /** Holds each onRawApiDataReceived until counted down; null for none. */
        volatile @Nullable CountDownLatch wedged;
        /** Holds each onRawApiDataBytes until counted down; null for none. */
        volatile @Nullable CountDownLatch wedgedBytes;

        @Override
        public void onRawFeedMessageReceived(
                UnparsedMessage message,
                MessageInterest messageInterest,
                RoutingKeyInfo routingKey,
                MessageTimestamp timestamp) {}

        @Override
        public void onRawApiDataReceived(URI uri, Object data) {
            events.add("onRawApiDataReceived " + uri + " " + data + " "
                    + Thread.currentThread().getName());
            await(wedged);
            if (throwOnReceived) {
                throw new IllegalStateException("thrown by the client");
            }
        }

        @Override
        public void onRawApiDataBytes(URI uri, byte[] body) {
            events.add("onRawApiDataBytes " + uri + " " + new String(body, StandardCharsets.UTF_8) + " "
                    + Thread.currentThread().getName());
            await(wedgedBytes);
        }

        private static void await(@Nullable CountDownLatch wedge) {
            if (wedge != null) {
                try {
                    wedge.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
