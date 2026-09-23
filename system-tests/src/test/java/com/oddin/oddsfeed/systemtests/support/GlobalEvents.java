package com.oddin.oddsfeed.systemtests.support;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Records the SDK's feed-wide events: connection loss and producer status changes. */
public final class GlobalEvents implements GlobalEventsListener {

  private final CountDownLatch connectionDown = new CountDownLatch(1);
  private final List<ProducerStatus> producerStatuses = new CopyOnWriteArrayList<>();

  /** Whether the SDK reported its feed connection down within {@code wait}. */
  public boolean awaitConnectionDown(Duration wait) throws InterruptedException {
    return connectionDown.await(wait.toMillis(), TimeUnit.MILLISECONDS);
  }

  public List<ProducerStatus> producerStatuses() {
    return List.copyOf(producerStatuses);
  }

  @Override
  public void onConnectionDown() {
    connectionDown.countDown();
  }

  @Override
  public void onProducerStatusChange(ProducerStatus producerStatus) {
    producerStatuses.add(producerStatus);
  }

  @Override
  public void onEventRecoveryCompleted(URN eventId, long requestId) {}
}
