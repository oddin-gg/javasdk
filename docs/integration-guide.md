# Integration guide

This guide takes a developer who has never used the SDK from the dependency to a running feed
that survives restarts. It describes the 1.0 line. The design behind it is [NEXT.md](../NEXT.md);
where this guide and that file disagree on a technical point, NEXT.md wins, and please tell us.

- [Configuration](configuration.md): every option, its default and its bounds
- [Health and logging](health-and-logging.md): `getHealth()`, the health events, what the SDK logs
- [FAQ](faq.md)
- Upgrading from 0.0.x: the release notes of each 1.0 release list every difference

Contents: [Getting started](#getting-started) - [Sessions and listeners](#sessions-and-listeners) -
[Closing](#closing) - [Resuming after a restart](#resuming-after-a-restart) -
[Onboarding checklist](#onboarding-checklist) - [Delivery guarantees](#delivery-guarantees) -
[Replay](#replay)

## Getting started

### Dependency

The 1.0 line is published to Maven Central as `gg.oddin.oddsfeed:odds-feed`. It needs Java 25. Its
packages are the 0.0.x packages (`com.oddin.oddsfeedsdk...`), so imports do not change.

```xml
<dependency>
  <groupId>gg.oddin.oddsfeed</groupId>
  <artifactId>odds-feed</artifactId>
  <version>1.0.0-rc.1</version>
</dependency>
```

```kotlin
// Gradle
implementation("gg.oddin.oddsfeed:odds-feed:1.0.0-rc.1")
```

Use the newest 1.0 version; `1.0.0-rc.1` is the first release candidate. Nothing else is needed
to fetch it: no registry and no token, unlike 0.0.x, which lives on GitHub Packages as
`com.oddin.oddsfeed:odds-feed`.

The SDK logs through SLF4J and nothing else, so put an SLF4J backend (Logback, Log4j 2, ...) on
your class path; without one SLF4J prints a warning and drops every line. The jar is an automatic
module named `gg.oddin.oddsfeed`.

### Configuration

An environment and an access token are required; everything else has a default (see
[Configuration](configuration.md)). `build()` throws `IllegalArgumentException` without either.

```java
OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
        .selectIntegration()                 // or selectTest(), selectProduction(), with a Region
        .setAccessToken(System.getenv("ODDIN_GG_ODDS_FEED_TOKEN"))
        .setSDKNodeId(1)                     // distinct for every running instance, see the checklist
        .build();
```

`selectIntegration(Region.AP_SOUTHEAST_1)` and the same for `selectTest` and `selectProduction`
pick a region; `selectEnvironment(messagingHost, apiHost)` names the hosts yourself. Your IP
address has to be allowed by Oddin before the API and the broker accept the token.

### Open

```java
OddsFeed oddsFeed = new OddsFeed(globalEventsListener, configuration);

oddsFeed.getSessionBuilder()
        .setMessageInterest(MessageInterest.ALL)
        .setListener(oddsFeedListener)
        .build();

oddsFeed.open();
```

Creating the `OddsFeed` does nothing yet. The feed starts lazily, on the first call of a manager
getter or of `getSessionBuilder()`: it asks the API who you are and which producers there are, and
builds the caches and managers. With the API down that start keeps trying for the startup timeout,
90 seconds by default, and then throws `InitException`; the next call tries again. A client that
wants a quick failure sets a shorter `setStartupTimeout`.

`open()` binds every session's queue, connects to the broker, and starts delivering. It is all or
nothing, and it is one-shot: once it has taken the sessions, a second call throws `InitException`,
whatever came of the first. After a failed `open()` call `close()` and build a new `OddsFeed`.

Build every session before `open()`. After it `build()` and `buildReplay()` throw
`IllegalStateException` (0.0.x returned a session that never received anything).

## Sessions and listeners

A feed has one or more sessions. Each has a message interest (which messages it takes) and a
listener. One session with `MessageInterest.ALL` is the simplest setup. Several sessions can split
the traffic, for example `HI_PRIORITY_ONLY` and `LOW_PRIORITY_ONLY`; `open()` throws
`UnsupportedMessageInterestCombination` for interests that cannot be combined, as 0.0.x did. A
session can also be limited to given matches with `setSpecificEventsOnly`.

Two listeners:

- `OddsFeedListener` receives a session's messages: `onOddsChange`, `onBetStop`,
  `onBetSettlement`, `onRollbackBetSettlement`, `onBetCancel`, `onRollbackBetCancel`,
  `onFixtureChange` and, optionally, `onUnparsableMessage`.
- `GlobalEventsListener` receives what concerns the feed as a whole: `onProducerStatusChange`,
  `onConnectionDown`, `onEventRecoveryCompleted`, and, new in 1.0, optional `default` methods
  such as `onConnectionStateChange`, `onProducerCauseChange`, `onHealthEvent`,
  `onSessionLagChange`, `onSafetyNetEvent`, `onCallbackFailure`, `onFatalError` and `onApiCall`.

A listener written for 0.0.x compiles unchanged: the 1.0 additions are `default` methods.

Which thread runs what:

- A session's callbacks run on a thread of that session, `oddsfeed-session-<id>`, one message at
  a time and in order. The SDK acknowledges a message to the broker after your callback returns,
  so a slow callback slows only its own session, and only that session's queue on the broker.
- Every `GlobalEventsListener` callback runs on one events thread. A slow one delays the events
  behind it, so do not do slow work, or entity loads from the API, in
  `onProducerStatusChange`.
- Entity getters such as `match.getName(locale)` are synchronous and may call the API when the
  cache is cold, on your thread. See [Configuration](configuration.md) for the eager preload that
  makes them warm.
- A callback that throws is logged and reported to `onCallbackFailure`; the session goes on and
  the message is acknowledged.

The cache is updated before your callback runs, so `match.getStatus()` inside `onOddsChange` sees
the state the message carried.

## Closing

```java
oddsFeed.close();
```

`close()` can be called at any time, more than once, and from a callback. It stops delivery, tells
every thread to stop, and waits for all of them together for the shutdown timeout, 5 seconds
unless `setShutdownTimeout` says otherwise. A callback still running at the deadline is left to
end on its own thread, and the log says so. After `close()` a feed does not start or open again.

Call `close()` after a failed start or a failed `open()` as well: the events thread and the
watchdog stay until you do. From `open()` to `close()` the feed keeps one non-daemon thread, so a
JVM whose `main` has returned stays up; a feed that is never closed keeps the JVM running.

## Resuming after a restart

Messages the SDK had not finished when your process stopped, or missed while it was down, are
recovered from the API when the feed opens. To ask for only what you missed, instead of a full
snapshot, save each producer's resume point and pass it back on the next start.

- `Producer.getTimestampForRecovery()` returns the resume point as an `Instant`, or null for a full
  recovery. Once the feed is open it is the point the sessions still need: it never runs ahead of
  what your callbacks have finished, and it can lag a little behind. Read it at shutdown, after
  `close()`: it keeps its last value, and `close()` makes it final. It is capped at the producer's
  own clock, so you never save a time in the producer's future.
- `ProducerManager.setProducerRecoveryFromTimestamp(producerId, epochMillis)` takes it back.
  **Call it before `open()`.** Once `open()` has begun the call is ignored and a warning is logged.
- It throws `IllegalArgumentException` when the point is older than the producer's stateful
  recovery window (`Producer.getStatefulRecoveryWindowInMinutes()`): you were down too long. Catch
  it and pass 0, which means no resume point.

```java
var producers = oddsFeed.getProducerManager();   // this starts the feed: it needs the API

// on start, before open()
var all = producers.getAvailableProducers().values();
for (var producer : all) {
    long saved = store.load(producer.getId());   // 0 when nothing was saved
    try {
        producers.setProducerRecoveryFromTimestamp(producer.getId(), saved);
    } catch (IllegalArgumentException tooOld) {
        producers.setProducerRecoveryFromTimestamp(producer.getId(), 0);   // full recovery
    }
}
oddsFeed.open();

// on shutdown
oddsFeed.close();
for (var producer : all) {
    Instant point = producer.getTimestampForRecovery();
    store.save(producer.getId(), point == null ? 0 : point.toEpochMilli());
}
```

Things to know:

- Save the point while the feed runs too, every few seconds or minutes, because a crash gives no
  shutdown to read it in. An older point is safe: it only makes the next recovery bigger.
- Resuming can repeat messages that were processed just before the stop, and misses none while the
  producer's clock only moves forward. Make your handling idempotent.
- Setting 0 asks for a full recovery. If you also set `setInitialSnapshotRecoveryInterval`, 0 means
  that interval instead: how far back a start with no resume point asks for. A start is also
  clamped to the producer's stateful recovery window.
- Every producer starts down after `open()` and comes up when its first recovery completes
  (`onProducerStatusChange`, reason `FIRST_RECOVERY_COMPLETED`).
- 0.0.x accepted the timestamp until a producer's first up, so setting it just after `open()` worked
  there. In 1.0 it must come before.
- 0.0.x reported the last alive as the recovery timestamp, which can run ahead of what was
  processed, so a client that persisted it could lose messages. 1.0 reports the point the sessions
  have processed.

## Onboarding checklist

Before you take the feed to production:

- [ ] **A distinct node id for every running instance.** Set it with `setSDKNodeId`. Node ids
      select node-addressed messages, such as the snapshots of the recoveries an instance asks
      for. Two instances that share one node id cannot be told apart by the SDK, and recovery
      messages meant for one reach the other. An instance with no node id and the message
      interest `ALL` takes the snapshot completes of every other instance of the bookmaker, so it
      can see thousands of them; with a node id it takes those of its node and of the instances
      that have none; any other interest takes those of its own node, or, with no node id, of
      the instances that have none. The SDK drops what it did not ask for, counts it as `recovery.unknown_completions`, and logs the first at INFO, with
      this advice, and the rest at DEBUG. A distinct node id on every instance ends it.
- [ ] **A queue length limit on the broker above the prefetch.** The SDK does not declare an
      `x-max-length` for its queues; the broker's operator may set one. If it is not larger than
      the configured prefetch (200 by default), the broker silently drops the oldest ready messages
      of a session that falls behind, and the SDK cannot see it. Check the limit that applies to
      you, and raise the prefetch only below it.
- [ ] **A memory budget per session.** A session holds at most `prefetch` messages, each at most
      `maximumMessageSize`, so its queue holds at most their product: 200 x 1 MiB = 200 MiB by
      default. On top of that the AMQP client assembles a body before the SDK can see its size, up
      to its own inbound limit of `max(64 MiB, maximumMessageSize + 1 MiB)` per channel, so one
      oversized body can be held briefly on each channel: 64 MiB by default, so 264 MiB per session
      with the defaults. A body over the maximum is not decoded, is counted, and is reported as
      unparsable; the limit sits above the maximum on purpose, so an oversized message is counted
      instead of closing the connection. The SDK's own alive channel has the same inbound limit.
      Size the heap for all sessions together. These are ceilings for the worst case, not what a
      feed of ordinary messages uses, and the numbers move with `setAmqpPrefetch` (1 to 10 000) and
      `setMaxMessageSize`.
- [ ] **Your IP address and token allowed by Oddin**, for the API and the broker.
- [ ] **Resume points saved and restored** ([above](#resuming-after-a-restart)).
- [ ] **Listeners that do not block.** A callback holds its session; watch `getHealth()` and
      `onHealthEvent` for a stalled one ([Health and logging](health-and-logging.md)).
- [ ] **Metrics and logs.** Export `getHealth().counters()` and keep the SDK's WARN and ERROR
      lines. The access token is the AMQP user name; see [the logging page](health-and-logging.md#logging).
- [ ] **A plan for `close()`.** After a fatal error (`onFatalError`) the client closes the feed
      and builds a new one.

## Delivery guarantees

Delivery is **at most once**. The broker's queues are exclusive and die with their connection, so a
message that was not acknowledged is never redelivered. The SDK acknowledges a message after your
callback returns, which gives backpressure (a slow listener slows its own queue on the broker, not
your heap) but not at-least-once.

The gap is closed by recovery. When a connection or a session's channel is lost, or a session is
reset by the safety net, or a producer goes silent, the SDK asks the API for a recovery of what the
producer sent since the point the sessions had reached. Recovery messages arrive on the sessions
like any others, and the producer is reported up again once the snapshot is complete. A message
that arrives after `close()` has begun is dropped unacknowledged, and the resume point covers it.

So a correct client treats `onProducerStatusChange` (down and up) as part of the data stream: down
means messages may be missing, and up means they have been recovered.

Fixture changes are the one place the SDK drops a message itself: the same change (producer, event
id and change timestamp) is delivered once per feed within an hour, whichever session it comes in.

## Replay

A replay session plays events the API recorded. It is the only session of its `OddsFeed`: build
it instead of live sessions, on a feed of its own. A replay feed runs no recovery and accepts none.

```java
oddsFeed.getSessionBuilder()
        .setListener(listener)
        .buildReplay();

oddsFeed.open();

var replay = oddsFeed.getReplayManager();
replay.addSportEvent(URN.parse("od:match:1"));
replay.play();                     // or play(speed, maxDelayInMs)

// ...
replay.stop();                     // stop playing
replay.clear();                    // stop and empty the list
```

`getReplayStatus()` tells what the replay server is doing.

**Replaying the same events again on the same feed within an hour delivers their fixture changes
only the first time.** The SDK drops a fixture change it has delivered already, and it keys that
on producer, event id and the change's timestamp, which a replayed event repeats. The dropped
ones are counted in `getHealth()` (`session.<id>.repeated_fixture_changes`), and 0.0.x does the
same. Odds changes, settlements and the rest are not affected. To see the fixture changes of a
replay again, close the feed and open a new one, or wait for the hour to pass.

A replay's messages are old by design: they write the caches in the order they come, and the safety
net is off for a replay session. As in 0.0.x, a field a new run leaves out keeps the earlier run's
value until a message of the new run replaces it.
