package com.oddin.oddsfeed.systemtests.support;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Records the SDK's feed-wide events: connection loss, producer status changes and completed
 * event recoveries. Like {@link Received}, waiting for one passes over - and consumes - the others
 * that arrive first, and names them when the one expected never comes.
 */
public final class GlobalEvents implements GlobalEventsListener {

  private final CountDownLatch connectionDown = new CountDownLatch(1);
  private final List<ProducerStatus> producerStatuses = new CopyOnWriteArrayList<>();
  private final BlockingQueue<ProducerStatus> unreadStatuses = new LinkedBlockingQueue<>();
  private final BlockingQueue<EventRecovery> eventRecoveries = new LinkedBlockingQueue<>();

  /** Whether the SDK reported its feed connection down within {@code wait}. */
  public boolean awaitConnectionDown(Duration wait) throws InterruptedException {
    return connectionDown.await(wait.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Every producer status change so far, oldest first, whether a test has waited for it or not. */
  public List<ProducerStatus> producerStatuses() {
    return List.copyOf(producerStatuses);
  }

  /**
   * The next status change of this producer, within {@link Received#DELIVERY}.
   *
   * @throws AssertionError if none arrives, naming every other status change that did
   */
  public ProducerStatus nextProducerStatus(long producerId) throws InterruptedException {
    long deadline = System.nanoTime() + Received.DELIVERY.toNanos();
    List<ProducerStatus> others = new ArrayList<>();
    while (true) {
      long left = Math.max(0, deadline - System.nanoTime());
      ProducerStatus status = unreadStatuses.poll(left, TimeUnit.NANOSECONDS);
      if (status == null) {
        throw new AssertionError("no status change of producer " + producerId
            + " reached the listener within " + Received.DELIVERY.toSeconds() + " s; "
            + (others.isEmpty()
                ? "nothing else arrived either"
                : "it got " + others.stream().map(GlobalEvents::describe).collect(Collectors.joining(", "))
                    + " instead"));
      }
      if (status.getProducer() != null && status.getProducer().getId() == producerId) {
        return status;
      }
      others.add(status);
    }
  }

  /** The next completed event recovery within {@code wait}, or empty. */
  public Optional<EventRecovery> pollEventRecovery(Duration wait) throws InterruptedException {
    return Optional.ofNullable(eventRecoveries.poll(wait.toMillis(), TimeUnit.MILLISECONDS));
  }

  /** "producer 1 down (OTHER)" - enough to tell status changes apart in a failure. */
  static String describe(ProducerStatus status) {
    String producer = status.getProducer() == null
        ? "unknown producer"
        : "producer " + status.getProducer().getId();
    return producer + (status.isDown() ? " down" : " up") + " (" + status.getProducerStatusReason() + ")";
  }

  @Override
  public void onConnectionDown() {
    connectionDown.countDown();
  }

  @Override
  public void onProducerStatusChange(ProducerStatus producerStatus) {
    producerStatuses.add(producerStatus);
    unreadStatuses.add(producerStatus);
  }

  @Override
  public void onEventRecoveryCompleted(URN eventId, long requestId) {
    eventRecoveries.add(new EventRecovery(eventId, requestId));
  }

  /** One {@link #onEventRecoveryCompleted} call. */
  public record EventRecovery(URN eventId, long requestId) {}
}
