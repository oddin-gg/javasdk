# Health and logging

[Integration guide](integration-guide.md) - [Configuration](configuration.md)

## Health

The SDK watches itself: the consumer, every session, the alive reader, the recovery thread, the
events thread, its own timer thread, the JVM's deadlocks that hold one of its threads, the
catalogs and the broker connection. Every internal wait has a deadline, so a wedge turns into a
timeout and a counter instead of a silent hang.

Each part is in one of three `HealthState`s:

| State | Meaning |
|---|---|
| `HEALTHY` | As it should be. |
| `DEGRADED` | Working, with a quality the design gives up on purpose: a catalog served stale, a session lagging with the safety net's resets spent, the broker connection down for longer than its limit. |
| `STALLED` | The watchdog found something not moving that the feed cannot unblock: a callback that has not returned, a queue that has not moved while not empty, a deadlock among the SDK's threads. The remedy is `close()` and a new feed. The watchdog never kills threads. |

### `getHealth()`

`oddsFeed.getHealth()` returns a `FeedHealth`:

- `state()`, the worst of the parts', and `components()`, a state for each `HealthComponent` the
  feed has now: `CONSUMER`, `SESSION`, `ALIVES`, `RECOVERY`, `EVENTS`, `TIMERS`, `THREADS`,
  `CATALOGS`, `CONNECTION`.
- Typed groups of counters: `transport()` (connected, reconnects), `alives()`, `sessions()` (one
  record per session: its state, whether it lags, queue depth, overflows, epoch discards, skipped
  acks, messages handled, unparsable, oversized, pipeline and callback failures, unknown
  producers, repeated fixture changes), `recovery()` (requests issued, re-issued, failed, timed
  out, abandoned, completed, event recoveries, the safety net's resets, the messages they dropped
  and its refused requests), `catalogs()` (state, stale for how long, failed fetches, evictions),
  `caches()` (dropped and failed side-loads, discarded stale fetches, dropped live states, dedup
  evictions) and `events()` (dropped control and telemetry events, dropped raw API data, callback
  failures).
- `counters()`, a `Map<String, Long>` with every value under a stable dotted name, such as
  `session.1.queue_depth` or `catalog.void_reasons.stale_for_millis`: states are 0, 1 and 2 for
  healthy, degraded and stalled, flags are 0 or 1, durations are milliseconds. Feed this to your
  metrics exporter.

It reads counters and flags on the caller's thread, never waits for a thread of the feed, and never
starts the feed. Before the start it has no parts; once started, the events, the catalogs, the timers
and the SDK's threads; once open, the consumer and the sessions, and the alives and the recovery
unless it is a replay feed. A closed feed still reads what it counted.

Every degradation the design allows is a log line, a counter and, for anything that discards data,
an event. Nothing is silent.

### Health events

Override `GlobalEventsListener.onHealthEvent(HealthEvent)` to be told of each change: the
component, the session for a session part (null for the others), the previous and the new state, a
reason in words, and the time. Each change is also logged: a stall as an error, a degradation as a
warning, a recovery as information. A part found healthy again is told the same way.

Events go through a slot per part in the events thread's queue, so behind a slow callback the newest
change replaces one still queued, with the state the client heard last as its previous.

Related events: `onSessionLagChange` (a session fell behind with the safety net's resets spent, or
caught up), `onSafetyNetEvent` (a reset made, or a recovery request the API refused),
`onConnectionStateChange` (`CONNECTING`, `UP`, `DOWN`, `RECOVERING`), `onFatalError` (a token the
API refuses, a login or virtual host the broker keeps refusing), `onCallbackFailure` and
`onProducerCauseChange` (the producer's status with its cause).

### Thresholds

Each has a default; set it on the builder.

| Option | Default | Bounds | `DEGRADED` or `STALLED` when |
|---|---|---|---|
| `setCatalogStaleLimit(Duration)` | 1 hour | positive, at most 1 day | a catalog has served a value stale, its refreshes failing, for this long: `DEGRADED` |
| `setConnectionDownLimit(Duration)` | 60 s | positive, at most 1 day | the broker connection has been down this long, counted from the first loss: `DEGRADED`; `HEALTHY` as soon as it is up |
| `setCallbackStallLimit(Duration)` | `max(30 s, 2 x HTTP timeout + 5 s)`, 65 s with the default timeout | positive, at most 1 day, and not below `2 x HTTP timeout + 5 s` | one callback, or one step of a thread's own, has run this long: `STALLED` |
| `setQueueStallLimit(Duration)` | as the callback limit | as the callback limit | a thread's queue has stood still, not empty, this long: `STALLED`; also how late the watchdog's own next look may be |
| `setWatchdogInterval(Duration)` | 5 s | 1 s to 1 minute | how often the watchdog looks; each look searches the JVM for deadlocks, which stops all threads for a moment |

The stall limits sit above the longest the SDK itself waits for the API in one getter, so a slow
API is no stall. `build()` refuses a stall limit below `2 x HTTP timeout + 5 s`, whichever of the two
options was set first; a limit set together with a short HTTP timeout may be below 30 s. A session
is `DEGRADED` while the recovery reports it lagging, told at once.

## Logging

The SDK logs through SLF4J and nothing else: you pick the backend and the levels. A logger is named
after its class, all under `com.oddin.oddsfeedsdk`, so you can turn a class or a package up or
down. Normal traffic is quiet: an hour of it logs state changes at INFO and nothing per message.

| Level | What |
|---|---|
| INFO | A state change: the feed opened, a producer up or down, a recovery started, ended or given up, a session caught up, a part of the feed healthy again. The one exception is API call logging, one line per attempt, off unless you set `setApiCallLogging(true)`. |
| WARN | Something you may need to act on, or should know is lost: a dropped message, a full queue, a failed preload, a degraded part. |
| ERROR | A part that stalled, or client code that threw. |
| DEBUG | The detail behind a warning, the stack of a failure, what happens per request. Nothing here is needed to run the feed. |

**Throttling.** A failure that can repeat per message, per call or per tick is logged at WARN or
ERROR the first time and then once in a thousand, with the count so far in the line (`..., 2000 so
far`); the others go to DEBUG with their stack. Some are limited by time instead: the first, then
at most one a minute.

**The access token is a secret in two places.** It travels in a request header of the REST calls,
so no URL the SDK logs holds it. It is also the user name of the broker connection, so the broker
quotes it in some of its replies. The SDK redacts the token from the failures it logs, the message
and the stack alike, and the broker's replies are redacted before they are logged. Your own
logging, proxies and broker-side tools see what the SDK cannot redact: treat the token as a
password, and keep it out of anything that records the AMQP login.

### Logger names

| Area | Package under `com.oddin.oddsfeedsdk` | Classes worth knowing |
|---|---|---|
| Feed | `.` and `.internal.feed` | `OddsFeed` (open and close), `OpenFeed`, `FeedCore` (startup), `HealthMonitor` (health changes), `Watchdog`, `ConnectionTee`, `RecoveryTee` (client listeners that throw) |
| Broker connection | `.internal.amqp` | `AmqpTransport` (connection, reopening, listeners), also the name of the AMQP client's own reports |
| Recovery | `.internal.recovery` | `RecoveryMachine` (each recovery, producer up and down, the safety net), `RecoveryActor`, `EventRecoveryStatuses` |
| Delivery | `.internal.dispatch` and `.internal.events` | `SessionDispatcher` (a session's messages and callbacks), `AliveDispatcher`, `EventsDispatcher` (global callbacks) |
| REST | `.internal.rest` | `RestTransport` (the API call log) |
| Messages and names | `.internal.message` | `MessageFactory`, `MarketNames`, `Naming` |
| Entities and catalogs | `.internal.entity` and `.internal.descriptions` | `Entities`, `MatchCaches` (the eager preload), `SportsInfo`, `Strategy` (what the exception strategy turns into null) |
| Producers | `.internal.producer` | `Producers` |
| Replay | `.internal.replay` | `Replay` |

To follow the connection and the recoveries as they go, set `com.oddin.oddsfeedsdk.internal.amqp`
and `com.oddin.oddsfeedsdk.internal.recovery` to DEBUG. To see each REST call, turn on API call
logging. Threads the SDK starts are named `oddsfeed-*` (`oddsfeed-session-<id>`,
`oddsfeed-timer`, `oddsfeed-keep-alive`), which helps reading thread dumps and log patterns.

With Logback:

```xml
<logger name="com.oddin.oddsfeedsdk.internal.recovery" level="DEBUG"/>
<logger name="com.oddin.oddsfeedsdk.internal.amqp" level="DEBUG"/>
```
