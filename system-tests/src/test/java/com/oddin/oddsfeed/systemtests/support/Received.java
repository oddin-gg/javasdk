package com.oddin.oddsfeed.systemtests.support;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.EventMessage;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A session listener that keeps every message it is given, in arrival order, for a test to take
 * one by one. Waiting for a kind of message passes over - and consumes - any others that arrive
 * first; when the one expected never comes, the failure says what arrived instead.
 */
public final class Received implements OddsFeedListener {

  /** How long a test waits for a message before calling it lost. */
  public static final Duration DELIVERY = Duration.ofSeconds(10);

  private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();

  /** The next message of this kind, within {@link #DELIVERY}. */
  public <M extends Message> M next(Class<M> kind) throws InterruptedException {
    return next(kind, DELIVERY);
  }

  /**
   * The next message of this kind, within {@code wait}.
   *
   * @throws AssertionError if none arrives, naming every other message that did
   */
  public <M extends Message> M next(Class<M> kind, Duration wait) throws InterruptedException {
    List<Message> others = new ArrayList<>();
    Optional<M> message = poll(kind, wait, others);
    if (message.isEmpty()) {
      throw new AssertionError("no " + kind.getSimpleName() + " reached the listener within "
          + wait.toSeconds() + " s; "
          + (others.isEmpty()
              ? "nothing else arrived either"
              : "it got " + others.stream().map(Received::describe).collect(Collectors.joining(", "))
                  + " instead"));
    }
    return message.get();
  }

  /** The next message of this kind within {@code wait}, or empty; for tests that retry. */
  public <M extends Message> Optional<M> poll(Class<M> kind, Duration wait) throws InterruptedException {
    return poll(kind, wait, new ArrayList<>());
  }

  private <M extends Message> Optional<M> poll(Class<M> kind, Duration wait, List<Message> others)
      throws InterruptedException {
    long deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      Message message = messages.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (message == null) {
        return Optional.empty();
      }
      if (kind.isInstance(message)) {
        return Optional.of(kind.cast(message));
      }
      others.add(message);
    }
  }

  /** "BetStop for od:match:198314 from producer 2" - enough to tell messages apart in a failure. */
  static String describe(Message message) {
    String kind = message.getClass().getSimpleName().replaceFirst("Impl$", "");
    String event = message instanceof EventMessage<?> eventMessage && eventMessage.getEvent() != null
        ? " for " + eventMessage.getEvent().getId()
        : message instanceof UnparsableMessage<?> unparsable && unparsable.getEvent() != null
            ? " for " + unparsable.getEvent().getId()
            : "";
    String producer = message.getProducer() == null ? "" : " from producer " + message.getProducer().getId();
    return kind + event + producer;
  }

  @Override
  public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
    messages.add(message);
  }

  @Override
  public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {
    messages.add(message);
  }

  @Override
  public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {
    messages.add(message);
  }

  @Override
  public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {
    messages.add(message);
  }

  @Override
  public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {
    messages.add(message);
  }

  @Override
  public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {
    messages.add(message);
  }

  @Override
  public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
    messages.add(message);
  }

  @Override
  public void onUnparsableMessage(OddsFeedSession session, UnparsableMessage<SportEvent> message) {
    messages.add(message);
  }
}
