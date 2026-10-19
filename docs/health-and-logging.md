# Health and logging

[Integration guide](integration-guide.md) - [Configuration](configuration.md)

## Health

The SDK watches itself: the consumer, every session, the alive reader, the recovery thread, the
events thread, its own timer thread, the JVM's deadlocks that hold one of its threads, the
catalogs, the broker connection and the API's acceptance of the access token. Every internal wait has a deadline, so a wedge turns into a
timeout and a counter instead of a silent hang.

Each part is in one of three `HealthState`s:

| State | Meaning |
|---|---|
| `HEALTHY` | As it should be. |
| `DEGRADED` | Working, with a quality the design gives up on purpose: a catalog served stale, a session lagging with the safety net's resets spent, the broker connection down for longer than its limit. |
| `STALLED` | Something not moving that the feed cannot unblock: a callback that has not returned, a queue that has not moved while not empty, a deadlock among the SDK's threads, a recovery thread that has ended (all found by the watchdog, which never kills threads), or an access token the API refuses. The remedy is `close()` and a new feed. |

### `getHealth()`

`oddsFeed.getHealth()` returns a `FeedHealth`:

- `state()`, the worst of the parts', and `components()`, a state for each `HealthComponent` the
  feed has now: `CONSUMER`, `SESSION`, `ALIVES`, `RECOVERY`, `EVENTS`, `TIMERS`, `THREADS`,
  `CATALOGS`, `CONNECTION`, `API`.
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
starts the feed. Before the start it has no parts, but for the API once the access token has been
refused: a start that failed on it reads the API stalled. Once started, the events, the catalogs, the
timers, the SDK's threads and the API; once open, the consumer and the sessions, and the alives and
the recovery unless it is a replay feed. A closed feed still reads what it counted.

Every degradation the design allows is a log line, a counter and, for anything that discards data,
an event. Nothing is silent.

**A token the API refuses** (HTTP 401 or 403, which no retry changes) is permanent: the call fails
at once and `onFatalError` is told once. It is also a health state, so a client that polls
`getHealth()` or registered its listener late sees it: `API` is `STALLED` from the first refusal,
told through `onHealthEvent` with the status, the call and the time in its reason (`the API
refused the access token: GET ... answered 403 at ...`), and logged at `ERROR`. It is `HEALTHY` again
when a REST call is answered with a success; the token is read when the feed is built, so in
practice it stays `STALLED` until a new feed. No other failure of the API changes it: a timeout, a
5xx or a 429 is not a refusal, and shows in the parts that depend on the API, such as the
catalogs, and in the recovery counters.

A recovery thread that has died (an `Error` or an exception that escaped its loop, which is a
bug of the SDK) is a stall too, and the feed can no longer recover: the SDK logs `The recovery
actor stopped` at `ERROR` with the cause and its stack, closes the actor, and refuses every later
event recovery (`recoverEvent` returns `null`). The watchdog reads that the thread has ended, so
`RECOVERY` is `STALLED` and `state()` with it from its next look, with no limit to wait for, told
once through `onHealthEvent` with the reason `the recovery thread ended: ` and the class and
message of the failure (the message cut at 200 characters; the stack is in the `ERROR` line). If
the part was `STALLED` already when the thread died, say in a turn that had run too long, the
client has heard that stall, and a `HealthEvent` is a change of state, so it is told no second one:
the log has the true reason, once, as an `ERROR` line `The feed's RECOVERY is still STALLED, for
another reason: the recovery thread ended: ...`. It stays so: nothing moves it again. No fatal
error event is raised. A `close()` of the feed is not this: the actor ends as asked and its part is
not reported as ended. Close the feed and open a new one; the `ERROR` line is the cause to report.

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
| INFO | A state change: the feed opened, a producer up or down, a recovery started, ended or given up, a session caught up, a part of the feed healthy again. Two exceptions: API call logging, one line per attempt, off unless you set `setApiCallLogging(true)`; and the first snapshot complete of another instance's recovery that reaches the feed, told once with the advice to set a node id (the rest of them are at DEBUG and in `recovery.unknown_completions`; see [the FAQ](faq.md#the-log-says-a-snapshot-complete-is-for-a-request-this-feed-did-not-ask-for)). |
| WARN | Something you may need to act on, or should know is lost: a dropped message, a full queue, a failed preload, a degraded part, a recovery that starts after where its gap began because the producer's stateful recovery window is shorter (the line says how much of the gap is dropped). |
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
