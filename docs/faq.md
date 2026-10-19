# FAQ

[Integration guide](integration-guide.md) - [Configuration](configuration.md) - [Health and logging](health-and-logging.md)

## Setup

### Which Java version does 1.0 need?

Java 25. The 0.0.x line, Java 8, is supported with critical fixes until 31 March 2027. The
dependency is `gg.oddin.oddsfeed:odds-feed` on Maven Central; see the
[integration guide](integration-guide.md#dependency).

### I get an SLF4J warning and see no SDK logs.

Put an SLF4J backend on the class path. The SDK uses the SLF4J API only.

### Can I run two feeds in one JVM, with different tokens?

Yes. Each `OddsFeed` has its own HTTP client, connection, caches and threads, and no global state.
Give each its own node id. Their threads are named alike (`oddsfeed-*`), and a deadlock among one
feed's threads counts against every feed in the JVM.

### How do I log which SDK version runs?

`OddsFeed.getSdkVersion()`. The SDK also sends it to the API and the broker.

## Starting

### `open()` throws `InitException`: "feed cannot already opened".

`open()` is one-shot: once it has taken the sessions, a second call throws, whatever came of the
first, including an `open()` that failed. Call `close()` and build a new `OddsFeed`.

### `build()` or `buildReplay()` throws `IllegalStateException`.

Sessions are built before `open()`. After it the builder refuses; 0.0.x returned a session that
never received anything.

### `open()` throws `IllegalStateException` without a session.

Build at least one session first. The feed can still be opened once you have.

### The first call on the feed takes a minute and a half, then throws.

The feed starts on the first manager call or `getSessionBuilder()`, and the start needs the API: who
you are and the producer list. With the API unreachable, it keeps trying for the startup timeout,
90 seconds by default, then throws `InitException`; the next call tries again and waits as long.
Set `setStartupTimeout` to fail sooner (it then also rides out a shorter outage). A client that gives
up on a feed should `close()` it.

### `open()` fails with the broker refusing the login.

`InitException`, with the broker's reason and the token left out. Check the token, the environment and that your IP address is
allowed. Once the feed
is open, refusals while reconnecting end it only when at least three have gone on for a whole minute
with no connection in between; `onFatalError` then carries the reason.

### The broker's certificate is not trusted.

1.0 checks the certificate and the host name against the JVM's trust (0.0.x trusted anything up to 0.0.58). For
your own truststore or a TLS-inspecting proxy, pass an `SSLContext` to `setMessagingSslContext`.

### I opened the feed and nothing arrives.

Check, in this order: that `open()` returned and the session was built before it; that the
producers you expect are not disabled (a producer no session asks for is disabled);
`getHealth()` and the log; `onProducerStatusChange`. Every producer starts down, and a status event
only comes when the down flag or the reason changes: an alive saying a down producer is unsubscribed raises none (use `onProducerCauseChange` to see
it). A producer comes up when its first recovery completes, with `FIRST_RECOVERY_COMPLETED`.

## Messages

### Can I lose a message? Can I get one twice?

Delivery is at most once; the gap a loss leaves is closed by recovery, not by redelivery. See
[Delivery guarantees](integration-guide.md#delivery-guarantees). Resuming after a restart can
repeat what was processed just before the stop, so handle messages idempotently.

### My callback runs on a different thread than in 0.0.x.

Message callbacks run on the session's own thread, `oddsfeed-session-<id>`, not on the AMQP client's
consumer thread. A `ThreadLocal`, an MDC or a thread name your callback relied on sees another thread.
Every `GlobalEventsListener` callback runs on one events thread.

### My callback is slow. What happens?

Unacknowledged messages pile up on that session's queue on the broker, up to the prefetch, and
the broker then stops delivering to that queue only. Other sessions go on. If the session stays
far behind, the safety net asks for a recovery and replaces its channel, dropping the backlog, up to
three times per ten minutes; after that the session is marked lagging (`onSessionLagChange`,
`getHealth()`). A callback running longer than the stall limit is reported `STALLED`.

### A fixture change I expected did not arrive.

The same fixture change (producer, event id, change timestamp) is delivered once per feed within an
hour, whichever session it comes in. See [Replay](integration-guide.md#replay).

### A message was reported unparsable.

The body did not decode, or it was over the maximum message size (1 MiB unless
`setMaxMessageSize` says otherwise). The latter is counted in `getHealth()` as oversized. A
message that decoded but that the SDK failed to write to its caches or to build goes to
`onCallbackFailure`, flagged as the SDK's, and to no session callback.

### `match.getName(...)` is slow inside my callback.

Entity getters are synchronous and call the API when the cache is cold, on your thread. Turn on
`setEagerEntityPreload(true)` and `setPreloadLocales(...)`; see
[Configuration](configuration.md#preload-locales-and-the-eager-preload).

### What do the getters do when the API fails?

That is the `ExceptionHandlingStrategy`, `THROW` by default. Under `THROW` an entity or catalog
getter that cannot load throws `ItemNotFoundException`, with the API's `ApiException` as its cause,
and a manager method that asks the API throws the `ApiException` itself. Under `CATCH` the getter logs
and returns null; a collection getter never returns a partial list. As in 0.0.x, under either strategy
the sports, active tournaments and market descriptions are an empty list when they cannot load, and
a market description by id is null.

## Producers and recovery

### Why is a producer down?

`onProducerStatusChange` gives 0.0.x's reason; `onProducerCauseChange` gives the cause in detail:
`UNSUBSCRIBED`, `ALIVE_INTERVAL_VIOLATION` (no alive for longer than the maximum inactivity, 20 s
by default), `PROCESSING_QUEUE_DELAY_VIOLATION` (a session processes the producer too late),
`CONNECTION_LOST`, `CHANNEL_LOST`, `SESSION_OPENED`, `SAFETY_NET_RESET`, `RECOVERY_FAILED`. It
comes back up when the recovery completes.

### How long does a recovery take, and what if it fails?

A producer's recovery waits 5 minutes at most for its `snapshot_complete`; a request the API refuses
or that times out is asked for again with backoff, three times in a row, then after a cool-down of
ten minutes or when an alive arrives after a gap. An event recovery is asked for with
`RecoveryManager`, and its status is `getEventRecoveryStatus(requestId)`: `PENDING`, `COMPLETED`,
`FAILED` or `TIMED_OUT`, kept five minutes after it ends.

### `initiateEventOddsMessagesRecovery` returns null.

The request was not accepted: the feed is not open yet (no queue exists for its messages), the
connection is down, 128 of that producer's event recoveries are in flight, a session's channel did
not come back in time, or the feed is closed or a replay feed.

### `setProducerRecoveryFromTimestamp` throws `IllegalArgumentException`.

The timestamp is older than the producer's stateful recovery window. Pass 0 for a full recovery.
See [Resuming after a restart](integration-guide.md#resuming-after-a-restart).

### I set the recovery timestamp and it was ignored.

It must be set before `open()`. After it, it is ignored with a warning in the log.

### The log says a snapshot complete is for a request this feed did not ask for.

Another instance of your bookmaker asked for a recovery, and its snapshot complete reached this
feed's queue too. The broker routes a completion by the node id of the request: a feed with no node
id and the message interest `ALL` takes those of every instance, one with a node id takes those of
its node and, with `ALL`, of the instances that have none. The SDK drops them, counts them
(`recovery.unknown_completions` in `getHealth().counters()`) and tells the first once at INFO; the
rest are at DEBUG, as there can be thousands. Nothing is lost. Give each instance its own node id
(`setSDKNodeId`), so that none of them runs without one; see the
[checklist](integration-guide.md#onboarding-checklist). If the INFO line names your node id and you
gave every instance its own, another instance runs with the same node id, which is a fault of the
setup.

### How do I know my node id is right?

It must differ between every instance that runs at the same time against the same account. See the
[checklist](integration-guide.md#onboarding-checklist).

## Replay

### I replayed a match again and got no fixture changes.

See [Replay](integration-guide.md#replay): within an hour the same feed drops fixture changes it
delivered already. Use a new feed, or wait.

### Can a replay session run next to a live one?

No. A replay session is the only session of its `OddsFeed`, as in 0.0.x.
