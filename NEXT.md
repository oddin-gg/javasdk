# Java SDK 1.0 – design plan

Status: the design `next` is built to, kept in step with it; section 13 logs each change.

This document describes how we rebuild the Java SDK. The short version: same public
API, same packages, a new group id, new insides. Pure Java 25, no Kotlin, no RxJava,
tests everywhere, and a release on Maven Central.

There are two versions of this design. The short one lives in our internal design-doc
space and is written for people. This file is the detailed one. It is written for
whoever implements a ticket, human or agent, and it is allowed to be long. When the
two disagree on a technical point, this file wins.

---

## 1. Why

The current SDK is Kotlin 1.6 on Java 8 with Gradle 7. It works, but it has problems
we keep running into:

- **Blocking work on the message thread.** Reading a property like `match.getStatus()`
  can trigger REST calls. Those calls run on the thread that delivers AMQP messages.
  A slow API or a large roster means the feed stalls for the client.
- **Deadlocks between caches.** Cache observers run inside other caches' locks. In
  0.0.49 to 0.0.54 two caches could lock each other forever. Because messages are
  auto-acknowledged, the client saw messages stop with nothing in the log.
- **No backpressure.** `autoAck=true` and no prefetch. A slow listener means unbounded
  memory, then a blocked broker connection.
- **Sequential fan-out.** Loading a match loads its competitors one by one, and each
  competitor loads its players one by one. Clients report minutes of startup time and
  work around it by calling REST directly.
- **Old dependencies.** `javax.xml.bind` blocks Spring Boot 3 users. Fuel uses a global
  singleton, so two `OddsFeed` instances with different tokens collide. Kotlin and
  RxJava on the classpath clash with clients' own versions.
- **Almost no tests.** Six test files, all added in 2026.
- **Weak observability.** Only a "connection down" event, no "connection up". Recovery
  gives back a bare request id. No SDK version in HTTP headers.
- **Missing features compared with the Go SDK.** Single-item getters, cache clear methods,
  default locale, timeouts, prefetch.

Fixing these one at a time inside the Kotlin code is not realistic. The threading
model is the problem, and it touches everything.

---

## 2. Decisions

Each decision has one line of reasoning. If you disagree, comment on the line.

| # | Decision | Why |
|---|---|---|
| 1 | Pure Java, target Java 25 | Latest LTS. Most clients are on 25 or can move. No Kotlin runtime to clash with. |
| 2 | Source compatibility, not binary | Clients recompile anyway when they bump the version. Lets us use records and `default` methods. |
| 3 | Same packages, new group id `gg.oddin.oddsfeed:odds-feed` | Every import stays as it is, so no source changes. Central verifies a group id against the matching domain, and ours is `oddin.gg`, so the old group id cannot be published there. Clients change one line and drop the registry and token setup entirely. |
| 4 | First version is 1.0.0 | Clear line between old and new. |
| 5 | Maven, multi-module | POMs stay buildable for years. Central publishing and API checks are standard plugins. Nobody on the team lives in Gradle. |
| 6 | No RxJava, no coroutines | Plain executors and virtual threads. Fewer concepts, fewer surprises. |
| 7 | `java.net.http.HttpClient` | Per instance, no global state, no dependency. |
| 8 | XML models generated from the vendored schema, with the old class names kept | One source of truth for all SDKs. Golden tests from the schema fixtures. Existing casts in client code keep working. |
| 9 | Manual ack after the listener callback returns, with prefetch | Backpressure that reaches the client code. A slow listener slows its own queue on the broker, not the JVM heap. |
| 10 | No client callback ever runs on an AMQP, alive, recovery or timer thread | Heartbeats, alive handling, recovery and timers can never be blocked by client code. |
| 11 | Caches never block. All waiting happens in loaders, never under a cache lock | The only reliable way to not deadlock, enforced by a dependency rule, not by convention. |
| 12 | Old 0.0.x line stays supported until 31 March 2027 | Two clients are still on Java 8. Critical fixes and additive wire fields only. |
| 13 | System tests first, before any new code | They run against the old and the new SDK. They are the proof that nothing broke. |
| 14 | Sidecar is on hold | Some clients do not want to run our binary. Keep the API layer separable so it stays possible later. |

Open decisions are in section 12.

---

## 3. What stays the same for clients

We promise **source compatibility** of the public API. Client code that compiles
against 0.0.x compiles against 1.0.0. Behaviour stays the same unless it was a bug.

### What "public API" means

The public API is every type reachable from the signatures of these entry points,
transitively, plus the entry points themselves:

- `OddsFeed`, `OddsFeedConfiguration` and its builder, `Environment`, `Region`,
  `ExceptionHandlingStrategy`
- Managers: `SportsInfoManager`, `MarketDescriptionManager`, `ProducerManager`,
  `RecoveryManager`, `ReplayManager`
- Sessions: `OddsFeedSession`, `ReplaySession`, `OddsFeedSessionBuilder`, `MessageInterest`
- Listeners: `GlobalEventsListener`, `OddsFeedListener`, `OddsFeedExtListener`
- Exceptions

"Reachable" is the rule, not a package prefix. It pulls in everything under
`api/entities` and `mq/entities`, the market description types under `api/factories`,
`URN` (which lives under `schema/utils`), `MessageTimestamp`, `RoutingKeyInfo`, and the
XML schema classes handed out by the extended listener. Every reachable type keeps its
package. Internal is the rest, named explicitly: every `*Impl` class, the `cache`
package, the `di` package, `ApiClient`, `ChannelConsumer`, `DispatchManager`,
`TaskManager`, `WhoAmIManager`. Kotlin made all of it public. The new code puts internal
types in packages whose name contains `internal`.

### Things we know are different and accept

1. Kotlin `data class` extras (`copy()`, `componentN()`) disappear. Nobody should be
   using them from Java. Kotlin callers also lose named arguments: Kotlin allows them
   only on Kotlin functions, so a call such as `Scoreboard(homeGoals = 1, ...)` or
   `match.getName(locale = english)` has to pass its arguments by position. Positional
   calls compile as before.
2. `MarketMessage.getMarkets()` becomes `List<? extends Market>`. Kotlin allowed the
   covariant override, Java does not. Code that assigns the result to `List<Market>`
   needs one cast.
3. Getters for attributes the feed never sends stay, are marked `@Deprecated`, and
   return null. Removing them would break compilation for someone.
4. The XML schema classes (`OF*` for the feed, `RA*` for REST) are generated from the
   schema instead of hand-written. They keep their names, packages and getters, so
   code that casts the payload of `onRawFeedMessageReceived` or `onRawApiDataReceived`
   to one of them keeps working. Where the aligned schema changed a type's shape, the
   generated class differs; those types are listed in ticket 12 and 13 and in the
   release notes. Both raw callbacks also get a new `default` method that delivers the
   raw XML bytes, which is what a raw listener should have offered from the start.
   The schema is vendored (section 9), so a shape change reaches this repo only
   through our own pull request, never by surprise. Within the 1.x line a schema bump
   that changes a public class's shape is not taken; additive schema changes are.
5. Internal types move to `internal` packages. Client code that imported `*Impl`
   classes, the caches or the API client directly stops compiling. Those types were
   never meant to be used and the examples never touch them.
6. The schema classes carry `jakarta.xml.bind` annotations instead of `javax.xml.bind`.
   They are plain objects with getters and stay usable as such. Client code that
   creates its own `JAXBContext` over them must move to Jakarta too.
7. Sessions are built before `open()`, which binds every session's queue once. After it,
   the builder's `build()` and `buildReplay()` throw `IllegalStateException`; 0.0.x
   returned a session that never received anything (KD-29).

### Behaviour that stays, and that the implementation must not "fix"

- Entity getters such as `match.getName(locale)` or `match.getCompetitors()` are
  synchronous and may fetch from REST when the cache is cold. They run on the caller's
  thread. A callback that touches a cold entity pays that latency on its own session
  only. Clients who want no fetch in their callbacks turn on the eager entity preload
  (`setEagerEntityPreload`, off by default, as 0.0.x loaded an entity on its first
  read): as the broker client hands a message to its session's queue, the match its
  routing key names is queued for loading in the default locale and the preload locales,
  each locale once, so by the time the session takes the message the match is loaded or
  loading, and a callback that reads it joins the load. A fixture change preloads nothing:
  the session invalidates its match before the callback. The load runs on a side-load worker, never on
  the broker client's consumer thread or a session's, where 0.0.54 lost messages to HTTP
  calls; the consumer thread only reads the routing key and queues, without waiting.
  Nothing waits for a preload: a message is delivered whether its match has loaded or
  not. A load of the same match in the same locale already queued or under way is not
  queued again, the side-loads' queue of 1 000 drops one that finds no room and counts
  it, and one that fails is logged - the first and every thousandth - and counted, both
  with the side-loads in `getHealth()`.
  The same holds on the events dispatcher: a getter inside a producer-status callback
  delays other events, which the documentation of that listener says.
- A cold market variant is one request, on the thread that reads its name, as in 0.0.x and
  the Go SDK, and 1.0 keeps it so (decided 2026-10-14). A dynamic market (the players of a
  match in a player-props market, say) has a description per variant, known only once a message
  carries it, so no preload can have it: the first `getName()` of each variant asks the API and the
  reader waits. A session's callbacks run one after another, so a restart whose callbacks read
  many variant names is paced by them, a request's latency each, whatever the preload does in
  parallel; the benchmarks README measures it, against the fake API and the test environment.
  The ways out are the client's: preload the locales it reads (the plain markets are then one
  list, read once), read names only for the markets it shows, and keep the reads off the session's
  thread - hand the message to the client's own workers, which may fetch several variants at a
  time - instead of reading every name in the callback. Nothing in the SDK reads a name the client
  does not ask for.
- A match's tournament comes only from its summary, as in 0.0.x and the Go SDK (`internal/cache/match.go`
  takes it from the summary alone), and 1.0 keeps it so (decided 2026-10-19). When the summary cannot be
  loaded, `getTournament()` returns null under `CATCH` and throws under `THROW`, even where the fixture
  names the tournament; the .NET SDK reads the tournament id from the fixture then (its #63). The fallback
  is a few lines and one more request on that failure path, and would be added, with a known-difference
  entry, if a client needs it.
- `ExceptionHandlingStrategy` keeps its meaning, getter by getter as 0.0.59 has it.
  Default stays `THROW`.
  - Under `THROW` a failure reaches the caller as the exception 0.0.x threw there. An
    entity or catalog getter that cannot load what it reads throws
    `ItemNotFoundException`, with the API's `ApiException` as its cause. A manager
    method that asks the API throws the `ApiException` itself: the schedules, the live
    matches, the fixture changes, a sport's available tournaments (which 1.0 holds for 24
    hours, KD-41), the void reasons.
  - Under `CATCH` the getter logs and returns null. A collection getter never returns a
    partial list: under `THROW` the first failed part fails it, under `CATCH` the whole
    collection is null.
  - 0.0.x's exceptions to both rules stay. Under either strategy the sports, the active
    tournaments and the market descriptions are an empty list when they cannot load,
    and a market description by id is null. A match's competitors are none under
    `CATCH`. A sport whose tournaments cannot load is left out of the active tournaments
    under `CATCH`. An outcome description's name and a match status description, which
    0.0.x read from what it held, are null when they cannot load, under `THROW` too. The
    name of a feed message's outcome still throws under `THROW`, as in 0.0.x.
  - A member list is its entity's id list: a match's or a tournament's competitors, a
    competitor's players. It returns at once, as 0.0.x's lazy members did: the members'
    profiles start loading in the background, on the side-loads a message's preload
    leaves idle, so a long list never crowds the preload out, and a reader of a member
    joins its load. A member whose profile cannot load is still listed, and its own
    getters fail.
  - Every collection a getter returns is a new mutable one, the caller's own.
- The cache is updated before the listener callback runs, so `match.getStatus()` inside
  `onOddsChange` sees the state the message carried, unless the cache turned the message down:
  one older than the last its producer wrote for the match, or whose corrected age is over the
  match status age (20 minutes), is delivered but writes no status (see the feed watermark below).
- Multi-session with the priority-split interests and the interest-combination
  validation stays exactly as today. A client may still create its own
  `SYSTEM_ALIVE_ONLY` session next to live sessions. A replay session stays the only
  session of its `OddsFeed`: 0.0.x gives it the interest `ALL`, which the validation
  allows only for a single session, so replay has always run on an instance of its own.
- Fixture-change deduplication across sessions stays with today's key: producer, event
  id and change timestamp, remembered for one hour. So a replay played again within the
  hour on the same feed has its fixture changes dropped, on both lines.
- Recovery messages are delivered to the client like any other message, on every
  session whose interest matches, as today.
- Recovery methods keep returning the request id as a `Long`. A new status lookup by
  request id is added next to them, not instead of them. As in 0.0.x the caller's thread
  waits for the API's answer, once the feed is open: for the HTTP timeout and a second at
  most, then null. A replay feed runs no recovery and accepts none, nor does a closed one.
- The feed starts lazily, as today: the first call of a manager getter or of
  `getSessionBuilder()` fetches whoami and the producer list (section 4, REST) and builds
  the caches, catalogs and managers. 0.0.x fetched only whoami, once; 1.0 tries both again
  within the startup deadline, 90 s by default, so with the API down a start takes that
  long to fail (KD-36). One start runs at a time, and callers that come
  meanwhile wait for it. A failed start throws `InitException` ("Failed to init odds
  feed", the reason as its cause), keeps nothing, and the next call starts again. The
  managers work before `open()`; an event recovery asked for before it is not accepted
  (null), since no queue exists yet for its messages. The events dispatcher is the feed's,
  started with its first start and kept by one that fails, so the client's events never run
  on two threads at once, and `close()` waits for a callback a failed start left running.
  `close()` releases what the start built and cuts a start under way short; after a failed
  start it has nothing to release and logs nothing (KD-15). A closed feed does not start
  again.
- `open()` is one-shot. After a fatal error the client closes the feed and creates a
  new one, as today. Unlike today, that holds for an `open()` that failed too, which
  0.0.x let the client call again (KD-34). Once `open()` has taken the sessions, a second
  call throws `InitException` ("feed cannot already opened", 0.0.x's words), whatever
  came of the first; only an `open()` without sessions ("Feed created without sessions")
  leaves the feed as it was. It checks the interests before it disables any producer or
  connects.
- `close()` can be called at any time, more than once, and from a callback: it stops
  delivery, tells every thread to stop, then waits for all of them within one shutdown
  timeout of five seconds unless `setShutdownTimeout` sets it (at most an hour), not one per
  thread; it does not wait for the callback it is called from, nor for an `open()` under way, which then fails and closes what it
  started. An interrupt does not cut short the wait for the sessions, the alive
  dispatcher and the recovery actor, so the resume points are final when `close()` returns;
  it is set again before it does. A callback still running at the deadline is left to end on its own daemon
  thread, and the log says so. The order: the recovery actor is told the feed is closing,
  then every dispatcher is told to stop and each session's close is posted to the actor;
  the dispatchers are waited for, then the actor, which handles what is queued within what
  is left of the deadline, then the connection closes, so the actor hears of no loss the
  close makes, and the sessions' queues drop what they still hold.
- Once `close()` has begun, no message reaches a session's listener, as in 0.0.x: a
  message a session's thread takes from then on is dropped unacknowledged, with nothing
  told to the recovery, and the resume point covers it. The sessions closing at shutdown
  complete no recovery and bring no producer up.
- From `open()` to `close()` the feed keeps one non-daemon thread, `oddsfeed-keep-alive`,
  as 0.0.x's executors were non-daemon: every other thread of the SDK is a daemon or a
  virtual thread, so without it a client whose `main` returned after `open()` would exit
  when the connection drops. A start that fails ends it, and so does `close()`, last; a
  feed that is never closed keeps the JVM running, as in 0.0.x.
- Delivery is at most once, as today. Exclusive queues die with the connection, so an
  unacknowledged message is never redelivered. The gap is closed by recovery, not by
  redelivery. Acking late buys backpressure, not at-least-once.

### How we check it

- The old `examples` compile against the new jar.
- Generated usage is the real source-compatibility gate. A test reads the last 0.0.x
  jar and writes code that calls every public method and constructor, reads every
  public field and implements every public interface of the public API, then compiles
  it against that jar (proving the code is right) and against 1.0. It catches what a
  class-file comparison cannot: a checked exception added to a method, an abstract
  method added to an interface a client implements, a return type a caller can no
  longer assign. Generated rather than written by hand, so it covers every signature
  and cannot drift.
- A class-file comparison walks the public entry points, collects every reachable type,
  and compares each with its 0.0.x counterpart: kind, supertypes, every public member
  with its generic signature and nullability. It fails on a reachable type in an
  `internal` package, and on any difference not on the **differences list**, a file in
  the repo that names every type and member added, changed or gone in 1.x, reviewed
  like code, and the source of the release notes.
- No jar diff: binary compatibility is not promised, so a report that could not gate
  would not be read.
- Binary compatibility is not promised. A client that reaches the SDK through an
  intermediate library compiled against 0.0.x can hit `NoSuchMethodError` at runtime.
  We know of no such library. The 1.0.0 version signals the change.

---

## 4. Architecture

Four layers, describing what depends on what. Control flows in both directions
through narrow interfaces: transport pushes connection events up, the recovery actor
asks transport to replace a session channel through a `SessionTransport.reset()` call
owned by ticket 21 and called by ticket 24, and wire classes travel to the extended
listener because section 3 says they must.

```
 public API      OddsFeed, sessions, managers, entity interfaces
 core            entity façades, caches, loaders, recovery actor, producers, replay
 wire            feed decoder, REST client, generated XML models
 transport       AMQP connection, HTTP client
```

### Threads

There are exactly these thread groups per `OddsFeed`, and every piece of code belongs
to one of them:

| Group | Count | Runs | Never runs |
|---|---|---|---|
| AMQP I/O | owned by the AMQP client | frame reading, heartbeats | anything of ours |
| AMQP consumer | one executor per connection, sized to sessions + 1 | hand-off of raw deliveries into session queues; the alive hand-off; telling the recovery actor of a lost channel, without blocking; with the eager preload, reading a queued delivery's routing key and queuing its match's load | decode, build, cache writes, REST, client code, anything blocking |
| AMQP reconnect and reopen | virtual threads | the reconnect loop; reopening a lost channel | client code |
| Session dispatcher | one thread per session | decode, build, cache write, client callback, ack, age sampling | nothing else |
| Alive dispatcher | one thread | alive decode, clock offsets, posting liveness facts to the recovery actor | REST, client code |
| Recovery actor | one thread | **all** producer and recovery state: liveness, checkpoints, completions, caps, resets, the safety-net decision; it looks at the clock itself at least once a second, paced by `System.nanoTime`, so a wall clock set back stops none of its ticks, and every duration it measures runs on the same clock | REST calls and channel resets (it posts them to REST workers and receives the result as a message), client code |
| Events dispatcher | one thread | every non-message client callback: connection state, producer status, health, fatal errors, listener exceptions, API call events, recovery completion | message callbacks |
| REST workers | virtual threads | HTTP calls, posting results back to whoever asked; an event recovery caller's answer, on a thread of its own | client code |
| Side-loads | virtual threads, half the REST concurrency limit (8 by default, 1 to 16) | background profile loads, the eager preload's match loads | client code |
| Watchdog | one thread, from the feed's first start to its close | every 5 s: the stall checks, the JVM's deadlock search, a read of the health | blocking work, client code |
| Keep-alive | one non-daemon thread, from `open()` to `close()` | nothing: it keeps the JVM up while the feed is open (section 3) | anything |

Decision 10 in one sentence: client code runs on a session dispatcher or on the events
dispatcher, nowhere else. The recovery actor in one sentence: nobody touches producer
or recovery state except by posting to it, so the state machine has one owner and no
locks.

The events dispatcher has two queues. The control queue carries connection state,
producer status and its cause, fatal errors, health, the safety net's events, sessions
lagging and recovery completion; it is bounded at 10 000 and a full queue is counted and
logged, never blocked on. Each producer's status, each producer's cause, each session's
lagging, the connection's state and each kind of fatal error (the API's, the broker's)
have one slot in it instead, which the newest fills, as the recovery actor keeps the alives: one still
queued is replaced rather than queued behind, and is delivered where its newest report
stands, after what was reported before it. So none of them is ever the one a full queue
drops, a client that falls behind hears the state as it is now and in the order it
changed - a producer's up reported after a connection loss is heard after it - and a
flood of them costs one queued entry each: reports that fill slots at once take turns, so
the one that replaces another always finds that one's place queued to take out. A
connection loss replaced that way is still told by `onConnectionDown`. The telemetry
queue carries API call events, listener-exception reports and the raw API data; it is
bounded at 1 000 and drops the oldest with a counter. The raw API data it holds, with
the response being delivered until both its callbacks are done, is bounded by bytes too,
at the largest response the REST decoder takes (32 MiB): a response with no room under
that is dropped and counted, since a thousand large responses behind a slow callback
would exhaust the heap. The raw feed messages are not queued there: their callbacks run
on the session's own thread, bounded by the session's queue. Each queue keeps its order,
and the control queue goes first: a telemetry event is delivered only while no control
event waits, so an API call reported before a connection change can be heard after it.
A wedged events dispatcher is reported by the watchdog through `getHealth()` and the log,
because the health event itself would queue behind the wedge.

### Delivery

- One AMQP connection per `OddsFeed`. One channel and one exclusive queue per session,
  as today. `basicQos(prefetch)` per channel. Prefetch is configurable in the range
  1 to 10 000, default 200. Zero is rejected, because the broker reads it as unlimited.
- The SDK always runs its own alive consumer on its own channel, whatever sessions the
  client created. Its consumer callback hands the raw alive to the alive dispatcher and
  acks. That hand-off holds at most a thousand alives and a megabyte of them; one with no
  room is dropped and counted. The producers' pace keeps it near empty, and a dropped
  alive costs the recovery actor a beat, not a wrong state: an unsubscribed producer says
  so in every alive, and silence takes a producer down. A lost alive channel counts
  against no producer, since only alives went with it, and the transport opens it again;
  if that takes longer than the maximum inactivity, silence takes the producers down. The
  Go SDK counts it against every producer. A client's `SYSTEM_ALIVE_ONLY` session, if any, is an ordinary session and does
  not carry producer liveness: its dispatcher posts no facts, and the recovery actor gives
  it no lanes, so its channel's loss or its pace takes no producer down and no recovery
  waits for its `snapshot_complete`.
- The consumer callback for a session channel does one thing: it puts the raw delivery
  (body bytes, envelope, delivery tag, channel epoch) into that session's queue. The
  queue holds at most `prefetch` entries per epoch and is drained of old epochs before
  a new channel's consumer is registered, so the broker's limit of `prefetch`
  unacknowledged messages per channel is the queue's bound. The hand-off never blocks
  and the consumer executor is never busy for longer than a queue put, so one session
  cannot delay another session's deliveries or the alive channel.
- A body larger than the configured maximum message size (default 1 MiB) is not
  decoded. It is counted, reported as unparsable, and acked. The SDK's queue for a
  session therefore holds at most `prefetch` times the maximum message size, and the
  configuration documentation says so. The AMQP client assembles a body before the SDK
  sees its size, up to its own inbound limit of max(64 MiB, maximum + 1 MiB), so one
  oversized body is held briefly on top. The limit is above the maximum on purpose, so
  an oversized message is counted rather than closing the connection.
- The session dispatcher takes a raw delivery, decodes it, writes the feed data into the
  caches, builds the message object, runs the client callback, then acks. The cache write
  comes before the build, as in 0.0.x: it does not need the message object, so a message
  the SDK cannot build still invalidates on a fixture change and still writes its live
  state. Order is preserved per session. A slow callback lets unacked messages pile up to `prefetch`
  on the broker, which then stops delivering to that queue and only that queue.
- Failures inside the dispatcher pipeline have one policy for every step: the
  exception is caught, counted, reported through the listener-exception hook on the
  global listener with a flag saying whether it came from client code or from the SDK,
  and the message is acked. A decode failure additionally reaches the
  unparsable-message callback. A build or cache-write failure does not reach the
  message callback, because there is no message object to deliver. The dispatcher
  survives every case. Nothing is ever nacked with requeue; that would loop a poison
  message.
- Every raw delivery carries the epoch of the channel it came from. Channel
  replacement (reconnect, safety-net reset, session close) follows one sequence, run
  by the AMQP layer on request of the recovery actor or of the lifecycle: cancel the
  old consumer, advance the epoch, remove every old-epoch entry from the session queue,
  declare the new queue, register the new consumer. An ack for an old-epoch tag is
  skipped. The discarded messages were on a queue the broker has already deleted;
  recovery covers them.
- When a session closes, its channel closes. Unacknowledged messages on that channel
  are dropped by the broker together with the exclusive queue. That is intended.
- The broker's own queue length limit is set by the operator, not by the SDK. The SDK
  does not declare `x-max-length`. The operator's limit must be larger than the
  configured prefetch, otherwise the broker drops the oldest ready messages silently
  and the safety net cannot see it; the configuration documentation and the onboarding
  checklist say so.

### REST

- Four permit pools per `OddsFeed`. The producer pool covers a producer's recovery
  requests and the producer and whoami calls, size 2, one for each of the API's two
  producers, so one producer's slow recovery cannot keep the other's from being sent. The
  recovery pool covers the recovery requests of single events, size 2: up to 128 of them
  can be in flight, and they must not queue ahead of a producer's, which is the safety
  net. The catalog pool covers market descriptions, void reasons, status descriptions and
  sports, size 2. The data pool covers entity loading, default 16, configurable. Nothing
  else can delay a producer's recovery request behind it.
  The side-load workers are half the data pool's size, at least one and at most 16, so
  the eager preload never takes more than half the permits and the client's own reads
  keep the rest, and it uses more of them the more the limit allows.
- Every REST call runs under one deadline, the configured HTTP timeout, which covers
  permit wait, the call, and every retry. Retries happen only inside the deadline. A
  load therefore waits at most the HTTP timeout and a second, and that is the number the
  configuration option documents. A getter can wait longer: it loads a second time when
  the first write was refused as stale, and a fan-out waits up to twice the timeout and
  three seconds, a long list more. Nothing remembers a failed load, so during an outage
  every cold getter waits that long again. That stays, by decision (2026-10-15, section 8,
  Outage).
- A permit is held for one HTTP call only, never across a fan-out. A match load
  releases its permit before its competitor loads acquire theirs, so nested loads
  cannot hold every permit in parents while children wait.
- Independent calls run in parallel on virtual threads, under the data pool.
- Retries only for idempotent calls, with backoff, inside the deadline. HTTP 429 and
  `Retry-After` are honoured within the same deadline. 401 and 403 are permanent: the
  call fails at once, nothing retries, and a fatal error event goes to the events
  dispatcher in addition to the caller's exception or null.
  The refusal is also a health state: the `API` part is `STALLED` from the first 401 or
  403, with its status, the call and the time in the reason, and `HEALTHY` again when a
  call is answered with a success (the token is read when the feed is built, so in
  practice it stays so until a new feed). A timeout, a 5xx or a 429 does not change it.
- The retry policy is the Go SDK's: at most three attempts, backoff from half a second
  doubling to five, with jitter. A 5xx or a failed connection is retried for reads and
  for recovery requests, which the API deduplicates on their request id, and not for
  replay control, which it would carry out twice. A 429 is retried for every call, since
  the API did nothing, after its `Retry-After` when that is longer than the backoff; one
  that asks for longer than the time left fails the call at once. The Go SDK ignores
  `Retry-After`. Replay control uses the data pool, so it cannot hold up recovery.
- Partial failure in a parallel fan-out: `THROW` fails the getter with the first error;
  `CATCH` returns null for the whole collection. Never a short list. A member list
  does not fan out: its members' profiles load in the background, and the list waits
  for none of them (section 3).
- Startup: the feed's start (section 3) needs whoami and the producer list. It retries
  them inside a startup deadline (default three times the HTTP timeout), then fails with
  a clear exception. It never blocks indefinitely and never starts half-configured.

### Caches and loaders

Structure:

- A cache is a bounded map with values, per-key metadata and no I/O. It never calls
  anything that can block. A **loader** owns the fetching: single-flight, permits,
  deadlines, merging a response into one or more caches. Loaders call caches; caches
  never call loaders. An architectural dependency test (ArchUnit or equivalent) fails
  the build if the cache package depends on the loader, HTTP or AMQP packages. This
  is the structural form of decision 11; the latch-based deadlock tests remain as a
  second net.
- Single-flight is in the loader. A fetch has one deadline, the HTTP client timeout
  from when it starts, and every REST call it makes draws from it; a load it waits
  for waits no longer than it. Concurrent misses for the same key wait for the one
  fetch until that deadline plus a margin, however late they joined. Waiters that time
  out fail with the exception strategy; they do not start their own fetch. A fetch
  still running past its waiters' time is abandoned and replaced by the next miss,
  and writes nothing from then on.
  A reader checks freshness before it asks the loader, and a fetch can write and leave in
  between: so the reader hands the loader the same check, and the loader makes it once more in
  the step that finds no fetch to join. A fetch writes before it leaves, so a reader that finds
  none and finds its entry fresh has missed one, and starts none; a fetch that wrote nothing, or
  an invalidation or clear after it (the entry is then a tombstone, not fresh), leaves the check
  failing, and the reader's own fetch starts. A fetch under way is joined without the check:
  it has written nothing yet. No second flag or generation: the check is the reader's own
  freshness test, so the generations and tombstones of the write rule stay what decides.
- Side-loading queues never block the producer. When full, they drop and count. Each
  side-load has one deadline too, from when a worker starts it. The eager preload's loads
  go to the main queue, one per match and locale at a time: a match already queued or
  under way in a locale is not queued again, so a burst of messages of one match takes
  one place.

Bounds and freshness:

- Caffeine. Entity caches have a maximum size and expire after write with the same
  ages as today: match, fixture and tournament 12 hours, competitor and player
  24 hours. The live state of a match is fresh for the match status age, 20 minutes,
  after the feed or REST last wrote it; past that the summary is loaded again. Freshness is tracked per locale block inside an
  entry: each locale's data carries its own fetch time, and a locale older than the
  entry's age is refetched on read even if another locale was written recently.
  Nothing is unbounded. No soft references.
- Catalog caches refresh after write, an hour after the fetch. An expired entry is served
  while the refresh runs and for as long as refreshes fail, with no age limit, as 0.0.x
  kept serving what it had: catalogs are reference data, and stale names are better than
  failing every market name after a long outage. An entry goes only when a fetch
  replaces it, a clear drops it or the size bound evicts it. Serving stale raises a
  health state, with how long the stalest entry has been stale. Failed refreshes back
  off per locale, and a dynamic market variant on its own, from a second doubling to a
  minute; while a locale backs off, a read with nothing to serve fails at once instead
  of waiting for another fetch. An empty list does not replace one with entries in it;
  it counts as a failed refresh.
- The caches hold entities, statuses and catalogs. They do not hold market state or
  odds; those live only in the messages the client receives.

Write rule:

- Every field of every cached entity has one **authoritative endpoint**. For a
  competitor the authoritative endpoint of its player list is the competitor profile;
  of its name per locale, the profile in that locale. The fields the feed owns are the
  exception, and are not in the entity caches at all (see ownership below). The full table is `odds-feed/CACHE-FIELDS.md`, with what 0.0.x wrote
  from where and the questions tickets 17 to 19 settle.
- An authoritative response replaces the fields it is authoritative for, in the locale
  it was fetched for, and marks them **authoritatively written**. A field the response
  omits is cleared and stays marked when the endpoint always serialises it when it
  exists; that is how a competitor's player list empties. A field the
  endpoint may leave out is kept as it is when it does, localized or not.
  Locale-independent fields are written by every authoritative response regardless of
  locale; two parallel locale fetches carry the same server state, so last writer
  wins is correct.
- A response from any other endpoint that happens to carry data for an entity **fills
  only**: it writes fields that are absent and were never authoritatively written. It
  never touches a field the authoritative endpoint has written or cleared, so a
  cleared value cannot resurrect from a later schedule or summary. Fill-only writes
  never set a loaded-locale mark; only an authoritative fetch for that locale does.
- A write never starts a fetch. Writes are synchronous, short, and take only the lock
  of the cache being written.
- Every cache entry carries its generation. Invalidation (a `fixture_change`, a public
  clear) bumps the generation and removes the value but keeps the entry as a
  **tombstone** with the new generation, bounded by the cache's own size and age. An
  authoritative fetch remembers the generation it started with and discards its result
  if the entry's generation differs or the entry is gone; the caller re-reads, which
  by then holds a fresh value or starts a fresh fetch. Fill-only writes check the
  same stamp, so a side-load from before an invalidation fills nothing; they differ
  only in what they write: what is absent and unmarked, and no locale marked loaded.
  A fetch its loader abandoned writes nothing either way.

Ownership and ordering:

- Feed messages own live status, scores, period scores, the match clock and the winner.
  REST owns everything else. Market state and odds are not cached.
- The winner has a rule of its own, since the feed sends it only once there is one and
  only the summary retracts it: a message that carries it writes it, one without it keeps
  it; the summary writes it, its absence clearing it, once the feed is quiet, as for the
  other feed-owned fields; while the feed is live, the summary's winner fills one the feed
  has not sent. A settlement right after the closing odds change reads the feed's.
- The feed-owned fields are not entity-cache fields, since they have two writers and
  only the watermarks know their order. They live in one bounded record per entity,
  the **live state**, next to the entity's watermarks; a value and its watermark change
  in one step, under the live state's one short lock, and a write there never waits for
  an entity cache or its clear. The feed writes there; a match summary offers its
  values there, and they are taken only while the feed is quiet (below). The match
  façade reads the live getters from it and everything else from the entity caches.
  Its bound follows the match cache size. Over it, the least recently written record
  the feed does not own goes, and the next read loads the summary again; records the
  feed owns stay, so REST does not take over a live match for want of room, up to
  twice the bound, past which the oldest are dropped and counted. Clearing the caches
  does not clear the live state.
- The feed watermark is the `timestamp` of the last live feed message that wrote
  feed-owned fields, kept per entity and producer in the live state, which lives 24
  hours after its last write. A message from the same producer with an older
  timestamp does not write feed-owned fields, with one exception for a watermark no
  delivery delay explains (below). It is still built and delivered; the
  watermark orders cache writes, never delivery. Messages that carry no feed-owned
  fields (settlements, cancels, bet stops) are not watermark-checked at all. Snapshot
  messages, recognisable by their recovery request id, write like live messages but
  never advance checkpoints (see recovery).
- The exception to the watermark's order. A timestamp in the producer's future - a
  component's fast clock, a bad value - would otherwise hold back every correct write
  that follows until the producer's clock passes it. A message older than the
  watermark writes, and takes the watermark down to its own timestamp and receipt,
  when all of these hold: (1) it was received strictly after every message that wrote
  for the producer, by the SDK's monotonic clock - the greatest receipt of the writes
  accepted for the entity and producer, kept apart from the watermark message's receipt
  and never moved back, since a message with a newer stamp received earlier than one
  before it may have written last - so two sessions handling messages in another order
  than they received them leave the order of receipt right; (2) the time between the
  latest of those receipts and its own, plus the gap between its timestamp and the
  watermark's, is more than the **future stamp margin**, 30 s by default: two deliveries that differ by more than that are not
  jitter, so either the older was delayed or the watermark is ahead of the producer;
  (3) its corrected age (the 20-minute rule's, by the producer's alives) is at most the
  **timely message age**, 10 s by default, which tells the first reading from the second:
  a message that sat in a backlog looks the same by (2) and is old by (3); (4) its
  timestamp is after the epoch, since the age of one far before it wraps around; (5) its
  timestamp is not below the one the watermark had before the first write ahead of the
  producer's now (below). A write whose corrected age is below the timely age negated -
  a stamp ahead of the producer, however little it raises the watermark - leaves behind the
  stamp it raised it from, per entity and producer, if none is left already. That stamp is
  kept through the stamps ahead that follow until a message takes the watermark over, which
  forgets it, since the next stamp ahead is judged from that message, or a message at the
  producer's now raises it (its age by the producer's alives within the timely age, on either
  side), which forgets it too: the watermark is then no further ahead than a timely message,
  and the stamp left behind is as stale as the quiet since it was written - a later stamp
  ahead would be judged by it and not by the correct write that came between. A late write,
  older than the timely age, leaves it as it is. An entity whose first stamp is itself ahead
  has no update before it: nothing is recorded, and the stamps ahead that follow record
  nothing either, so a correct message after them takes over. A write with no corrected age, a replayed
  message or a recovery's answer, says nothing of the producer's now: the stamp is left behind
  by a jump there, a raise of more than the margin more than the time since the latest
  receipt of a write for the producer (a quiet match's next message raises it by as much as
  the quiet lasted, which is no jump). Without it, a correct update U written first and a
  bad F handled by another session after it leave a watermark that has forgotten U, and
  an ordinary older message that follows U by a moment would take over and put U's
  score back. The limit: of two bad stamps in a row, the second older than the first,
  the second takes over and the stamp before the first is forgotten with it. The
  arithmetic is overflow-free on any long. A wrong takeover needs a delivery
  more than the margin slower than another's and a producer's alive late by more than
  margin less timely age, 20 s by default, which is already an inactivity violation; and
  a watermark at the producer's now is no more than the timely age ahead of or behind a
  timely message, so the two cannot pass the margin by R + G if the timely age is at most
  half of it: that is the limit, and the setters are checked together in `build()` (the
  timely age defaults to half the margin if that is shorter). Both are settings of the
  configuration builder, `setFutureStampMargin` and `setTimelyMessageAge`; the margin is
  at most a day, a day being as good as off. A stamp ahead by less than the margin cannot
  be told from a delay and holds the following writes back until the stamps catch up,
  at most the margin; of two stamps ahead the one received last writes when the same
  rule holds for it (as 0.0.x writes every one), and stamps closer than the margin leave
  the first in charge until they catch up; two components of one match whose clocks are more than the margin apart are
  not protected, as in 0.0.x. A replay session never takes this path. **A message that
  answers a recovery request (it carries a request id) never takes the exception:** a
  recovery must not move the live state back, and the next live message repairs a bad
  stamp; it is written by the plain order of the watermark. The one condition is
  `SessionDispatcher.mayTakeOver`.
- Feed and REST clocks are never compared directly. Two rules replace the comparison:
  - REST writes feed-owned fields only when the entity has no feed watermark, or when
    the watermark is older than the match status age (20 minutes) by the SDK's own
    receipt clock. A live match is owned by the feed; a match the feed has gone quiet
    on falls back to REST.
  - A feed message whose corrected age (section on the safety net) exceeds the same
    20 minutes does not write feed-owned fields either. A message that old is a
    delayed backlog message, and REST has since taken over. It is still delivered.
    The offset that corrects it is the producer's, which the alive dispatcher measures
    on the SDK's alive channel and keeps for the session dispatchers to read without
    asking the recovery actor, as of the reading the dispatcher took the message at, so a
    step of the wall clock since the alive does not move the age (the Clocks rule);
    before the producer's first alive it is taken as none.
    A replay session's messages are old by design and write as current, in the order
    they come: the watermark does not hold them back, so a replay played again writes
    each run; the watermark itself never moves back. As in 0.0.x, a field the new run
    leaves out keeps the earlier run's value until a message of the new run replaces it;
    starting each run empty needs every delivery tagged with its run (ticket 26).
- Within a feed message, a missing optional scalar means "keep what you have", never
  "reset to zero". Both schemas mark scores optional.
- Fixture-change deduplication is one shared, concurrent map per `OddsFeed`, keyed by
  producer, event id and change timestamp, bounded in size and expiring after one
  hour. Evictions before expiry are counted. All session dispatchers consult it before
  delivering.
- Caches, producer state and checkpoints belong to one `OddsFeed` instance. A replay
  session is the only session of its instance (section 3), so replayed messages never
  share caches, producer state or checkpoints with live ones.

Locales and catalogs:

- Loaded-locale marks are stored with the values they describe and share their
  lifetime. A mark is set only by an authoritative fetch for that locale. New sports,
  tournaments and markets show up without a restart.
- Catalog entries carry their provenance: bulk-listed or individually fetched. A
  refresh of a list endpoint replaces the bulk-listed entries for that locale and
  leaves individually fetched ones (dynamic market variants) alone; those expire on
  their own age. A market or tournament removed upstream disappears on the next
  refresh. A market, void reason or match status missing from a list fetches the list
  again, for any missing id and every time, only when the list is at least a minute
  old, so one added upstream shows up within about a minute, however often it was asked
  for before; ids that are never there cost one fetch of the list a minute at most. The
  read that missed waits for that fetch, within its deadline, and finds what is new: like a getter on a cold entity
  (section 3), it pays the latency on its own session, as in 0.0.x. While the list's
  last fetch failed, the read does not wait but starts a background refresh, so an
  outage does not cost a timeout each time the backoff ends.
- Every cache has a public clear method.

### Recovery and producers

All of this state lives in the recovery actor. Sessions, the alive dispatcher, timers
and REST workers post facts to it; it decides and posts work out.

- Recovery is a state machine per producer, with tests that drive it through every
  transition.
- Request ids start from a random 31-bit seed per process and increase by one; on
  reaching the range end the actor reseeds. The random start makes a restart within
  the same second unlikely to reuse ids. The actor keeps the set of ids it has in
  flight; a completion for any other id is ignored and counted. Two instances sharing
  one node id cannot be told apart by the SDK, so the documentation and the onboarding
  checklist require distinct node ids per instance.
- Checkpoints are kept **per producer and per session**. A session's checkpoint for a
  producer is the running maximum of the `timestamp` of the live (non-snapshot)
  messages and the subscribed alives it has finished for that producer. Every session's
  queue is bound to the alives, as in 0.0.x, and one queue keeps the producer's order,
  so an alive the session has finished means everything sent before it has been
  processed: that is the alive-based advance, and it needs no look into the queue. A
  session that has seen a recovery's `snapshot_complete` has everything up to the
  request, so its checkpoint moves there. Snapshot messages never advance a checkpoint,
  so a retry after a partial snapshot starts from the same point as the first attempt.
- What a recovery must cover is kept as gaps. A queue that loses what it held - the
  connection or its channel lost, a safety-net reset - and a session that opens miss
  everything after the session's checkpoint. A producer that stops sending - an alive
  that says the feed is unsubscribed, or no alive for longer than the maximum
  inactivity - leaves every session missing what came after its last subscribed alive
  on the SDK's alive channel, which says everything before it was sent; the queues
  still hold what they had, so a slow session does not drag that recovery back. A
  recovery starts from the oldest open gap: after a lost connection, the oldest
  checkpoint among the sessions that receive the producer. A gap keeps its start until
  a recovery that covers it completes. A client-supplied recovery-from timestamp
  (existing setter) seeds every session's checkpoint at `open()`; set once `open()` has
  begun, it is ignored with a warning (0.0.x took it until the first up). To resume after a
  restart, a client persists the producer's resume point, the oldest of its open gaps'
  starts and its sessions' checkpoints, and passes it back through
  `setProducerRecoveryFromTimestamp`. `Producer.getTimestampForRecovery()` reports it
  once the feed is open, as the actor publishes it after each fact, and the client's own
  value before; 0.0.x reported the last alive, which can be ahead of both (KD-28).
  `getLastProcessedMessageGenTimestamp()` is moved by the client's sessions only, with
  every message they process, not by the SDK's alive channel (KD-38). A session closed while
  the feed runs no longer counts. Once the feed begins to close, the point only goes
  back, so what the client reads at shutdown does not depend on the order the sessions
  close in; a gap of the producer's that opens after its last session has closed still
  takes the point back. The façade tells the actor `closing()` before it closes any session, and the
  actor's own close does the same, then handles the essential facts already queued,
  for two seconds at most, before its machine closes: an unsubscribed alive among them
  still takes the point back, and so does one whose post was under way as the close came,
  which the actor waits for. Facts it has no time for take each point back to the
  producer's last subscribed alive, where such an alive's gap would start, or to a full
  snapshot without one. A close that comes before the actor's thread has run handles
  nothing, so nothing is published after it returns. The resume point is not
  clamped: passed back after a downtime longer than the stateful recovery window, the
  setter throws `IllegalArgumentException`, as in 0.0.x, and the getter's documentation
  tells the client to catch it and pass 0 for a full recovery. Ticket 31 documents
  resuming. Both the published point and a recovery's start are capped at the
  producer's now - the SDK's clock corrected by the offset measured on the producer's
  last alive - so one message or alive stamped in the producer's future, from a
  component's fast clock or a bad value, moves neither past what the producer has
  sent: a request is not refused for a start ahead of the producer, and a client does
  not persist one. The marks the recovery takes from a message - a session's checkpoint, a
  producer's safe point, the start of a gap with no safe point - take its stamp as it is,
  unless it is more than a second ahead of the producer's now at receipt, and then that
  now: the SDK's wall clock when the delivery arrived, less the producer's most cautious
  offset of the last minute - the largest, which puts the producer's now earliest. An alive
  stamped ahead shows a small offset, so it does not lift it, and does not vouch for its own
  stamp because its mark is taken before its offset is kept and the session's copies of it take
  that mark; a message received before it and handled after is clamped as it was. An alive
  that is late, or stamped behind, shows the largest offset and pulls the now towards it: what
  follows is marked lower by its delay for the minute. None while no alive has shown one. A producer clock that steps back is taken at
  once, one that steps forward, or is really ahead, a minute later. A mark that is lowered
  only makes a recovery larger, so every error is on the safe side; the second is the
  jitter a recovery's mark absorbs, so the stamp of an ordinary message is the mark exactly,
  and a delivery that took more than a second longer than the longest alive of the minute
  lowers the mark of what it carries for that minute. There is no tolerance setting. A
  clamped stamp is logged at `WARN`, at most once a minute per producer, with the stamp, the
  producer's now and the difference. The stamps a client sees, on the message and in the
  producer's last processed timestamp, stay as the producer made them; one place,
  `ClockOffsets.mark`, computes the mark for the alive dispatcher and the session
  dispatchers, which hand it to the recovery actor; the session's copy of an alive takes the
  mark the alive channel took from the same alive, whichever copy is handled first, so the
  offset an alive shows cannot vouch for it. A replay session takes its stamps as they are.
  The live state takes the stamp as it is, and orders its writes by the exception to
  the watermark's order (Ownership and ordering), not by a mark. A
  recovery's start is clamped
  to the producer's stateful recovery window, as today - counted back by the producer's
  clock, as the gaps' starts are, with the offset measured on its alives - and a cold
  start with no seed requests a full snapshot, or the configured initial snapshot
  interval, counted back from when the gap opened, so asking again does not move it.
  That start is also the session's checkpoint until it processes something, so a later
  loss starts from it too, rather than from an interval counted back from the loss.
- A recovery is asked for only while an alive says the producer is there, as 0.0.x
  asks at the first alive: after `open()`, after a reconnect, after a gap in the
  alives. Nothing goes out while the connection is down, nor before the transport's
  first up, which it tells once every session's channel is bound: a snapshot sent
  earlier reaches no queue, and its `snapshot_complete` is lost with it. So the actor
  takes the connection's events from the transport itself, not through the events
  dispatcher's queue (ticket 26), and starts with the connection not up. In the same
  way nothing is asked for a producer while a session that receives it has lost its
  channel: the transport tells the loss (`ChannelEvents.lost()`) and the new channel
  once its queue is bound (`reopened()`, for every loss told, whether the transport's
  own reopen, a reconnect or a reset opened it), and ticket 26 posts both to the
  session's `SessionFacts`. An event recovery asked for meanwhile waits, as one does
  during a reset.
- Session lifecycle: sessions open at `open()` and close with the feed (section 3,
  difference 7), but the actor takes a session opening or closing at any time. A
  session that closes leaves the checkpoint and completion sets at once. A session
  that opens is seeded with the producer's current recovery-from point and triggers a
  recovery for its interests, as today on `open()`; the producers it receives are down
  until that recovery completes.
- Snapshot completion is tracked per session, unlike today (KD-37): a producer is up
  again when every session that receives it and takes snapshot completions has seen
  its `snapshot_complete` (a low-priority session next to a high-priority one takes
  none). An event recovery completes the same way, and its completion reaches the
  client. A recovery no session takes completions for, as when the high-priority
  session of a pair closed, completes once the API has accepted it, since nothing else
  would ever complete it.
- Requests for one producer are coalesced: while a recovery is in flight, further
  triggers join it instead of issuing a second one, and their reasons are recorded. A
  recovery covers the gaps open when it was asked for; a gap that opens while it is in
  flight asks for one more once it completes - another session's, say, which leaves the
  recovery's snapshot on its way. A producer gap is different, since the producer may
  have lost the request with its state: an alive still unsubscribed after the API
  accepted the recovery, or no alive for longer than the maximum inactivity, gives the
  recovery up, uncounted, and the next alive asks again, where 0.0.x and the .NET SDK
  ask again at once (KD-26). An unsubscribed alive before the API's answer joins: the
  producer may not have seen the request yet. A loss that takes the recovery's own
  snapshot with it - the connection, or the channel of a session that has not seen the
  `snapshot_complete` yet - gives the recovery up, uncounted, and a new one is asked for.
- A producer's recovery waits five minutes at most for its `snapshot_complete`: five
  minutes after it was asked for, or after a session that awaits it last took a message
  of its snapshot, or one the snapshot queues behind - a message or an alive sent before
  the request, a message of one of the producer's last eight recoveries that failed or
  were given up, or of an event recovery asked for before it. An event recovery asked
  for since does not count: one after another, they would keep the producer down for
  the maximum recovery time. A slow session is not given up while its snapshot is
  still coming; a lost
  `snapshot_complete` - the Go SDK saw one on the test environment, on a bound and
  consuming queue - costs minutes, not the maximum recovery time. Five minutes is the
  Go SDK's deadline (recoveries completed there in 83 to 139 s), and 0.0.58's, checked
  there at each alive; 0.0.57 and older and the .NET SDK have none and wait out the
  maximum recovery time (KD-25). That time stays the bound on
  a recovery that keeps coming, and on an event recovery the API has not answered. The builder's
  `setMaxRecoveryExecutionMinutes` sets it; its default stays 0.0.x's 360 minutes, and it
  takes no less. 0.0.x had no setter; its public constructor, which 1.0 keeps, takes any value.
- Recovery that the API does not accept, or that times out (either deadline), is
  re-issued with backoff from five seconds, doubling, at most three times in a row.
  After that the producer stays down and the client gets a producer-status event with
  the reason. The cap re-arms after a cool-down of ten minutes, and immediately when an
  alive arrives after a gap. Nothing stays down for the process lifetime without a
  further attempt.
- Event recoveries take their ids from the same sequence. At most 128 are in flight per
  producer, and one the API accepted but whose `snapshot_complete` does not come is
  dropped and counted, as is one whose `snapshot_complete` went with a lost queue: it
  times out - status `TIMED_OUT` - five minutes after the API accepted it, or after the
  first `snapshot_complete` of a session, or after a session that still awaits it last
  took a message of its snapshot or one the snapshot queues behind - a message or an alive
  sent before the request, a message of the producer's recovery in flight asked for before
  it, or of an event recovery asked for before it - so a slow, big snapshot that keeps
  arriving, or waits behind one that does, is not cut; a repeated `snapshot_complete` of
  a session that gave one already is no sign of life; one the API has not answered yet
  waits for the maximum recovery time, which stays the outer bound. The client is not asked again: event
  recoveries are the client's to ask for. A caller still waiting for the API's answer
  then hears it was not accepted. The first
  `snapshot_complete` of a request, from any session, says the API took it: an event
  recovery's caller hears the request id then, while the completion still waits for the
  other sessions, and for a producer or an event recovery alike a late answer, even a
  failure, changes nothing - no second request, no failure counted. One asked
  for while the connection is down is refused at once, since its snapshot would have no
  queue to go to. The caller's future is completed on a thread of its own, never the
  actor's, so nothing the caller chains to it can hold the actor up.
- Each event recovery has a status by its request id, as in the Go SDK: pending from
  the request, then completed, failed or timed out. Failed means the API refused it, its
  `snapshot_complete` went with a lost queue, or the feed closed first; timed out means
  nothing of its snapshot, nor of what queues ahead of it, came for five minutes after the
  API accepted it or after the last of it came. Unlike the Go
  SDK, a producer going down for another reason fails none, since its snapshot can still
  arrive. A status is kept five minutes after it ends, counted by the monotonic clock,
  then forgotten; an id never asked
  for, or forgotten, has none, and producer recoveries have none. Pending ones are bounded
  by the event recoveries in flight; of the ended ones at most 10,000 are kept, and past
  that the first to end is forgotten early, and counted. The actor writes the statuses
  and any thread reads them; `RecoveryManager.getEventRecoveryStatus(requestId)` returns
  them as the public `EventRecoveryStatus`, whose `State` is `PENDING`, `COMPLETED`,
  `FAILED` or `TIMED_OUT`.
- Clocks. Every duration the recovery machine measures between two local events - the
  inactivity of a producer, the deadlines of a recovery and of an event recovery, the
  backoffs, the cool-down, the safety net's windows, and how long an event recovery's status
  is kept - runs on a monotonic clock (`System.nanoTime`), so a change of the wall clock
  cannot take a producer down, fire a deadline or hold one back: a wall clock that steps by
  more than the maximum inactivity, or a virtual machine that resumes after a pause, leaves the
  producers up and asks for no recovery. Every time the SDK publishes stays wall time:
  `getLastMessageTimestamp`, the resume point and a recovery's start, the issue time of a
  recovery, the instants of status changes and of event recovery statuses. Only the producer's
  timestamps are compared with the SDK's wall clock - the clock offset, a message's age, the
  cap at the producer's now and the start of a stateful recovery window. A step of the wall
  clock since an offset was measured does not show in them: the offset is measured on an alive
  together with the difference of the fact's two readings, wall less monotonic, which no step
  moves, and wherever the offset is read - a sample's age, the age that keeps a stale message from
  writing the live state, the cap, the window, the producer's
  now a recovery is stamped with - it is moved by how far that difference has since moved, when
  it has moved by more than a second (less is jitter and slew, and is left). The producer's
  clock is taken not to have moved with the SDK's, so the next alive measures the same offset
  the correction already made. A fact carries both readings, taken where it is received,
  because the actor handles it later. One `FeedClock` per feed gives both readings to the transport, the
  dispatchers and the actor. The rule covers the caches as well: the age of a cached entity
  and of a catalog's value, how long the catalog has served one stale, a backoff, how long an
  invalidation is remembered, the match status age that decides whether the feed or REST owns a
  match's live status, a loader's deadline and the time its callers wait, and the hour a
  fixture change is remembered, are all measured on the feed's one clock, monotonic side, which
  is handed to the caches where the feed builds them. A feed write is stamped with the
  monotonic reading of when it was received. What a cache compares with a producer's own
  timestamp stays wall time, and so do the instants in an error message.
- The facts posted to the actor are of two kinds. Those whose loss would leave the
  state wrong for good - sessions opening and closing, the connection going and coming,
  the alives from the SDK's alive channel (an unsubscribed one is the only word of a
  gap), `snapshot_complete`s, lost and reopened channels, the API's answers, finished
  resets - are never dropped and keep their order. The alives are kept per producer of
  the list: the latest, and of the first unsubscribed one and the last subscribed one
  before it, so a flood of them costs one slot and one queued fact per producer, and an
  alive of a producer the list does not have is dropped and counted. Of the rest of
  that queue, sessions, connection changes, the API's answers and finished resets are
  bounded by what the feed itself does; lost and reopened channels by the transport's
  own reopening; and the
  `snapshot_complete`s, one queued per request per session until handled, by the
  recoveries this feed asked for: one of a request id it did not ask for - another
  instance's sharing the node id, or one the binding takes of an instance with another or none - is
  dropped and counted as it is posted, which the machine would have ignored anyway.
  The rest - event recovery requests, and the messages and alives a session finished -
  are bounded and dropped with a count when full; a dropped request is answered as not
  accepted. The lesser queues yield to the essential one: after taking a fact, the actor
  handles every essential fact waiting before it, and whoever posts an essential fact
  and then a sample puts the essential fact in first, so a sample from a new channel is
  never handled before the loss of the old one. Each turn takes at most a thousand of
  the bounded facts of each kind, so a stream of them holds up neither the clock nor the
  others.
- Producer status keeps 0.0.x's public reasons. Each change also names its cause:
  unsubscribed, no alive, processed late, connection lost, channel lost, session
  opened, safety-net reset, recovery failed, and the recoveries and the catching up that
  bring it back. The 0.0.x status callback fires when the down flag or the public reason
  changes, as in 0.0.x, so an alive saying a producer still down is unsubscribed raises
  none (KD-2); every change of the cause, those included, is reported apart, to
  `onProducerCauseChange`, whose `ProducerStatusCause` names it. Alives are checked from `open()`: a
  producer without one for longer than the maximum inactivity, 20 s by default, is
  reported down with `ALIVE_INTERVAL_VIOLATION`, one never up included, without 0.0.x's
  minute of grace (KD-7). A
  session that processes a producer later than the maximum inactivity takes the
  producer down with `PROCESSING_QUEUE_DELAY_VIOLATION`, without a recovery, and on
  time again brings it back with `RETURNED_FROM_INACTIVITY`, as in 0.0.x. A recovery
  that completes while a session processes its producer late leaves the producer down
  for that, rather than up for the moment until the next look at the delay. A
  producer that comes back with nothing missing, as when the session whose gap held it
  down closes, starts its retries afresh: a cap spent on that gap does not hold up the
  next one. The first time a producer comes up after `open()`, whatever brings it up -
  its first recovery, a follow-up one, a session closing, the session catching up - the
  reason is `FIRST_RECOVERY_COMPLETED`, so the client hears it once per producer, as in
  0.0.x.
- The safety net. Message rates depend on what a client has booked, and the SDK does
  not promise to keep up with every queue. Backpressure protects the JVM and the
  broker connection. The safety net bounds how far behind a client can fall: past a
  point, one recovery snapshot is cheaper than processing a long stale backlog message
  by message. The rule, decided by the recovery actor from facts the sessions post:
  - Age of a message is its producer timestamp against the SDK clock corrected by the
    offset measured for **that producer** on its own alives. If a producer's last alive
    is older than two alive intervals, its offset is stale and the net is disabled for
    that producer until alives resume. Age is sampled when the dispatcher takes the
    message, so it includes both broker backlog and the session's own queue.
  - Snapshot messages are excluded from the age sample on every session, and the net
    is paused for a producer, on every session, while a recovery for that producer is
    in flight or a gap of it is open; a sample taken before the recovery completed does
    not count after it. This stops one session's snapshot from tripping another
    session. An event recovery does not pause the net: its messages are snapshot
    messages, excluded already.
  - When the age of live messages from a producer stays above the configured limit for
    the configured window, the actor first requests a recovery for every producer the
    session receives that it can ask for now, each from its checkpoint on that session,
    since the reset drops the messages of all of them. It waits while one of them has a
    recovery in flight, but not for one that is silent, has its cap spent or waits out a
    backoff: that one sends the session nothing to drop, and once the reset is done it
    misses what the queue held like the others, to be asked for when it can be. Only
    when every request has been accepted does it ask
    the AMQP layer to replace the session's channel. A rejected or failed request means
    no reset: the actor backs off, counts, and raises an event. The session's own
    `snapshot_complete` of one of those recoveries, seen before the reset, cancels it,
    since the reset would now drop what the recovery sent - and only that does. Another
    session's completion is no evidence that this session's backlog was replaced: a
    low-priority session next to a high-priority one sees no completions, so its reset
    goes ahead once its requests are accepted, and the gaps it reopens ask for what the
    recoveries had sent into the old queue. Data is never dropped before its
    replacement is on the way. When the reset is handed to the AMQP layer, every lane of
    the session misses what came after its checkpoint then, before any message of the
    new channel can move it. The reset stays pending until the AMQP layer reports it
    done; meanwhile the session's `snapshot_complete`s, from the queue being replaced,
    are ignored, and nothing more is asked for its producers, whose recoveries are in
    flight. Once done, the session counts as having lost its queue: the
    recoveries asked for before the reset may have sent part of what they brought into
    the old queue, so they are given up and asked for again. A reset therefore costs a
    second recovery of the session's producers, which the first, accepted, has shown the
    API will take. The reset takes the session's producers down until those recoveries
    complete. A reset already with the AMQP layer stays one through a lost connection or
    channel, since replacing the channel late could still drop what the new one holds:
    the session's producers are asked for nothing until it is reported done. The AMQP
    layer reports a reset done only once the session reads from an open channel again:
    one it could not open at once it opens on its own, and a recovery asked for before
    then would send to no one. An event recovery asked for while a session that receives
    its producer is being reset waits until the reset is done, since its snapshot could
    go to either channel; one that waits longer than the maximum recovery time expires
    like one asked for. A reset that could not be handed to the AMQP layer at all, or that
    failed before the old channel's deliveries were taken out - the channel's epoch tells,
    since it moves exactly then - is reported as not made: nothing was dropped, nothing
    counts against the cap, the net waits as long as after the last reset made - its
    backoff does not grow - and the recoveries whose `snapshot_complete` was ignored
    meanwhile are asked for again. The net's numbers - the limit of two minutes, the window
    of one, three resets per session per ten minutes, a minute's backoff doubling - are
    decided defaults. The limit and the window are options (`setStaleMessageLimit`,
    `setStaleMessageWindow`, each up to a day, which is as good as off). The limit must be
    more than the 10 s between a producer's alives, since the delivery's jitter alone would
    make a healthy session look stale under a smaller one, and the window at least the
    actor's tick of a second, which measures it; the rest are fixed.
  - Each reset raises an event (`onSafetyNetEvent`, as does a refused request) and
    increments counters (resets, messages dropped by the reset, epoch discards), so an
    operator can see exactly when and why.
  - The net backs off between resets, a minute after the first and doubling within the
    cool-down, and has its own cap of three per session per cool-down; only a reset the
    AMQP layer has made counts, and raises the event. When spent, the net stops
    resetting: messages keep flowing under
    backpressure, the session is marked "lagging" in `getHealth()` and
    `onSessionLagChange` tells it,
    and the producer is **not** marked down, because a slow consumer on one session is
    not a producer fault and other sessions may be healthy. Backpressure wins in the
    end; the net gets three tries to shortcut it.
  - Recovery messages reach every session of the producer, not only the one that
    reset. Healthy sessions process them as ordinary messages, as today with any
    recovery.
  - It is off for replay sessions, whose messages are old by design.
- Unknown producer ids are an error, not a fabricated producer.

### Connection

- Connection events: connecting, up, down, recovering. No duplicate "down" on a normal
  close.
- The connection is made as the Go SDK makes it: TLS with the broker's certificate and
  host name checked (0.0.x trusted any certificate up to 0.0.58, and checks it since 0.0.59), the
  virtual host whoami names (0.0.x built `/oddinfeed/<bookmaker id>`, the same today), a 10
  second heartbeat, and the host as configured, tried once - the client's own resolver tries
  every address of a host, and a refused login on the first then reads as a network failure on the last.
- Reconnect with backoff on network failures. Exclusive queues are always re-declared;
  whatever the broker buffered for the old queue is gone, and recovery covers it.
- A channel the broker closes or cancels while the connection stays up is opened again
  by the transport, with a pause from a second, doubling to thirty while it fails; 0.0.x
  left it closed (KD-27). The session is told of the loss before the new channel can
  deliver, and of the new channel once its queue is bound, and recovery covers what the
  old queue held.
  A channel the broker keeps refusing - a queue limit, a binding it will not take - is
  retried for as long as it takes, each failure counted (`getHealth().transport()
  .channelRefusals()`, `transport.channel_refusals`); the connection is `DEGRADED` once one
  channel has been refused three times in a row and the streak has gone on for the
  connection's down limit, until the next channel opens (see Watchdog and health).
- Authentication and authorisation failures and a wrong virtual host are treated as
  permanent once at least three refusals have gone on for a whole minute with no
  connection in between. They are counted by the time, by `System.nanoTime`, not by
  attempts, which the backoff spaces a few seconds apart at first, because a refusal can be an auth backend
  being deployed or a virtual host being written. Permanent means the reconnect loop
  stops and a fatal error event carries the broker's reason, with how many refusals
  over how long. The client's exit is `close()` and a
  new `OddsFeed`; `open()` is one-shot (KD-34).
- Broker resource limits (connections, queues) are transient. They are retried with a
  long backoff and surface as an error event each time, never as a silent hang.
- `open()` is all or nothing. It creates the connection, the alive consumer and every
  session's channel and queue; if any step fails, everything created so far is closed,
  nothing has delivered a message, and `open()` throws. The client may call `open()`
  on a fresh instance again.

### Wire

- XML models are generated from the vendored schema. Binding customisations keep the
  old class names, packages and getters (section 3).
- The decoder is the untrusted-input boundary: JAXB on Woodstox, chosen by name, not
  by whatever StAX provider the application brings. Woodstox ships inside the SDK's jar,
  relocated and without its service registration, so it never becomes the application's
  StAX parser, which would ignore the JDK's XML hardening the application may rely on.
  DTDs off, external entities off, text parsed as it is reached, not lazily. Woodstox
  searches colliding names one by one, and its hash seed does not part names built to
  share a `String` hash, so a document may use at most 512 distinct names - elements,
  attributes, prefixes and namespaces - at most 8 of them with one hash; no element,
  attribute or prefix name longer than 128 characters; an element at most 256
  attributes and namespace declarations together, seven times the widest element's 35; an attribute value, a declared namespace
  included, at most 16 384 characters; and each document's names stay its own. Processing instructions
  are refused. Body
  size is bounded before decoding by the maximum message size from the delivery
  section; the decoder's own limits bound parser work. One malformed document costs
  one unparsable callback, nothing more.
- In the generated XML classes (`OF*`, `RA*`), unknown enum values decode to an
  `UNKNOWN` constant, and the class keeps the raw value in a separate getter next to the
  enum getter (`getStatus()` and `getStatusRaw()`; the feed's enums are numbers, so the
  raw value is the number as decoded). The public message enums in `mq.entities`, such
  as `MarketStatus` and `FixtureChangeType`, stay source compatible: no new constants,
  no new getters, and an unknown wire value behaves as in 0.0.x. An unknown fixture
  change type reads `OTHER_CHANGE`. A message with an unknown market status is still
  delivered, and the getter that meets the status - `getMarkets()` of an odds change,
  `getMarketStatus()` of a bet stop - throws a `NullPointerException`, as in 0.0.x.
  Unknown attributes and elements
  are ignored in production and fail the golden tests, so producer drift shows up in
  CI, not at a client.

### Watchdog and health

The SDK checks itself. It watches the consumer executor, every session dispatcher, the
alive dispatcher, the recovery actor, the events dispatcher, the timer executor, and
`ThreadMXBean` for deadlocks that hold one of the SDK's own threads. Every internal
blocking wait in the SDK has a deadline, so a wedge on a permit, a latch or a queue, which
`ThreadMXBean` cannot see, turns into a timeout with a counter instead of a silent hang. A
dispatcher inside one callback for longer than the configured limit, an executor whose queue
has not moved while non-empty, or a reported deadlock of the SDK's threads produces a loud
log line, a health event on the global listener (`onHealthEvent`: the component, its previous and new `HealthState`, a
reason), and a state change in `getHealth()`. The states are `HEALTHY`, `DEGRADED`
(working, with a quality the design deliberately gives up, such as a catalog served stale
or a session lagging) and `STALLED` (something the watchdog found not moving, which the
feed cannot unblock; the remedy is `close()` and a new feed).

`getHealth()` exposes the counters for every degradation the design deliberately
allows: dropped side-loads, catalogs served stale and for how long, unparsable
messages, oversized messages, callback and pipeline exceptions, discarded stale fetches,
dedup evictions, safety-net resets and the messages they dropped, epoch discards,
recovery requests issued, re-issued and failed, reconnects, events queue overflows,
and per-session queue depth and lag state. Silent degradation is a log line plus a
counter plus, for anything that discards data, an event.

`getHealth()` returns a `FeedHealth`: the feed's state, the worst of its parts', a state
per `HealthComponent` the feed has now, and typed groups of counters - the transport
(connected, reconnects, channel refusals), the alives, one record per session (its state, lagging, queue
depth, overflows, epoch discards, skipped acks, messages handled, unparsable, oversized,
pipeline and callback failures, unknown producers, repeated fixture changes), the
recovery (requests issued, re-issued, failed, timed out, abandoned, completed, unknown
completions, the event recoveries', the safety net's resets, the messages they dropped
and its refused requests, the actor's dropped and failed facts), each catalog (its state,
served stale and for how long, failed fetches, evictions), the caches (dropped and failed
side-loads, discarded stale fetches, forgotten invalidations, dropped live states, dedup
evictions) and the events thread (dropped control and telemetry events, dropped raw API
data, callback failures). `counters()` has every value under a stable dotted name
(`session.1.queue_depth`, `catalog.void_reasons.stale_for_millis`) for a metrics
exporter. It reads counters and flags on the caller's thread, never waits for a thread of
the feed and never starts it: before the start it has no part, once started the events,
the catalogs, the timers, the SDK's own threads and the API, once open the consumer and the sessions,
and the alives and the recovery unless it is a replay feed; once closed it still reads
what was counted.

The thresholds are builder options, each with today's number unless set. A catalog that
has served a value stale for an hour or more (`setCatalogStaleLimit`) is `DEGRADED`, found
when the health is read (by `getHealth()`, and by the watchdog's tick, so no reader is
needed); so is the broker connection once it has been down for longer than 60 s
(`setConnectionDownLimit`), from the first loss the transport told, and it is `HEALTHY`
again as soon as it is up, told at once. The connection is also `DEGRADED`, with its
reason naming the channel (a session's or the alive one) and the last failure, while a
channel the broker refuses to open again on the live connection has been refused three
times in a row and the streak has lasted that same limit; the next channel to open ends the
streak, so does a lost connection (its time down is counted instead) and the close. A
session is `DEGRADED` while the recovery actor reports it lagging, told at once. The API is
`STALLED`, told at once, from the first answer of 401 or 403 until a call is answered with a
success: the REST client tells the health of each attempt, and a refusal is the one thing
that changes the part (a timeout, a 5xx or a 429 shows in the catalogs and the recovery
counters instead). The watchdog's limits sit above the SDK's own waits, so a
slow API is no stall: a callback running (`setCallbackStallLimit`), or a queue not moving
(`setQueueStallLimit`), for longer than the longest the SDK itself waits for the API in one
getter - a read loads twice at most, each load waiting the HTTP timeout and the loaders'
1 s margin, and the read 1 s more, `2 × (timeout + 1 s) + 1 s` - and 2 s more is
`STALLED`: unless set, `max(30 s, 2 × timeout + 5 s)`, 65 s for the default timeout of
30 s, checked every 5 s (`setWatchdogInterval`, a second to a minute, since each look
searches for deadlocks at a safepoint). A stall limit set below `2 × timeout + 5 s` is
refused when the configuration is built, whichever was set first, so no option can call a
slow API a stall; a limit set with a short timeout may be below the 30 s the default keeps.
The catalogs', the connection's and the stall limits take a day at most. Each
change of a part's state is logged - a stall as an error, a degradation as a warning, a
part getting better as information - and told to `onHealthEvent`, through a slot per part
(per session for a session's) in the events dispatcher's control queue: behind a slow
client the newest change replaces one still queued, with the state the client heard last
as its previous. A part found healthy again - a callback returned, a queue moved, a
deadlock of the SDK's threads ended - is told so the same way.

The watchdog is one daemon thread, `oddsfeed-timer`, started with the feed's first start
(the events thread and the catalogs exist from then; the consumer, the sessions, the
alives and the recovery actor join once the feed opens) and stopped first by `close()`,
within its one shutdown deadline. Once stopped, the timers are `HEALTHY`, told so if they
were found stalled - a watchdog stopped is not one wedged - and every other part stays as
the watchdog last found it, since nothing looks again. At each tick it reads, for every
part with a thread of its own, when the callback it runs began (0 when none runs), what
waits for it and how much it has taken - every time by `System.nanoTime`, the watchdog's
own looks too, so a change of the wall clock neither stalls a part nor hides a stall: a
session (`busySince`, its queue's depth, `handled`), the events thread (`busySince`, the
control events, filled slots and telemetry queued, the events delivered), the broker's
consumer threads (a pool of the transport's own that keeps when the hand-off running
longest began, the hand-offs waiting and those run), the alive dispatcher (its queue and
`handled`; it has no callback worth a limit) and the recovery actor (`turnedAt` as its
busy-since - it begins a turn every second however idle, paced by `System.nanoTime` so a
wall clock set back does not idle it, so a turn begun longer ago than the limit is one not
ended - its facts waiting and `turns`). A queue is still from the first look that
sees it not empty with the same count taken; any look that sees the count move, or the
queue empty, ends that. The JVM's deadlocks come from
`ThreadMXBean.findDeadlockedThreads()`, which sees monitors and locks alike, once a tick
only, since the search takes the JVM to a safepoint. Only the SDK's own threads count: every
thread the SDK starts is named `oddsfeed-*`, and a deadlock holding none of them, one among
the client's own threads, leaves the feed's health as it is; a deadlock between a client
thread and an SDK thread (client code inside a callback) is `STALLED`, and the reason names
the SDK's threads. Another feed's threads in the same JVM are named alike and count too. The
watchdog cannot see its own thread wedge, so `getHealth()` looks at the parts again on
the caller's thread, and finds the timers `STALLED` when the next tick is later than the
queue's limit; the tick that returns tells them healthy. The looks decide under one lock that only
reads counters and flags, and note what they decide there, in order; what is logged is
logged out of it. A look that throws is logged and the next one runs. The connection's
part, `CONNECTION`, is the feed's once it is open, as the consumer's; `onConnectionDown` and
the connection's state callback still tell each loss as it happens.

What a dead thread does: an `Error` or an exception that escapes the recovery actor's loop (a
bug; a fact's failure is caught and the actor goes on) is logged at `ERROR` as "The recovery
actor stopped", then recorded on the actor (`endedBy`: the failure's class and message, the
message cut at 200 characters), then the actor closes itself and turns every later event recovery
away. The watchdog's sample of the actor carries the record, so the `RECOVERY` part reads
`STALLED` at the look that finds it - no stall limit to wait for - with the reason "the recovery
thread ended: <class>: <message>", told once and never healthy again; `getHealth().state()` is
`STALLED` with it. A close of the feed is not a death: it records nothing, and the part is not
reported as ended. No fatal error event is raised, and the watchdog does not end or restart the
actor: the remedy is `close()` and a new feed. Pinned by `OddsFeedWatchdogTest`, `WatchdogTest`
and `RecoveryActorTest`.

Remediation is limited and stated: the watchdog does not kill threads. A wedged
dispatcher is reported; the client's remedy is `close()` and a new `OddsFeed`, and
`close()` uses the shutdown timeout so a wedged callback cannot block shutdown. A stall
must never be silent again, but the SDK cannot unwedge client code.

### Logging

The SDK logs through SLF4J and nothing else: the client picks the backend and the levels.
A logger is named after its class, so a client turns one class or one package up or down.
Normal traffic is quiet: an hour of it logs state changes at INFO and nothing per message.

Levels:

- INFO: a state change. The feed opened, a producer up or down, a recovery started, ended
  or given up, a session caught up, a part of the feed back to healthy. Two exceptions: the
  opt-in API call log, one line per attempt, off unless the client asks; and the first
  snapshot complete of a request the feed did not ask for, told once per feed with the advice
  to set a node id (the rest are counted and at DEBUG: another instance's recoveries reach a
  feed with no node id by the thousand, and 0.0.x logged each at INFO).
- WARN: something the client may need to act on, or should know is lost: a dropped
  message, a queue full, a failed preload, a part of the feed degraded, a recovery
  clamped to the producer's stateful window (it names the producer, the gap's start, the
  start asked for and how much is dropped; once per gap, its re-issues at DEBUG). A
  warning that can repeat is throttled.
- ERROR: a part that stalled, or client code that threw. Also throttled when it can repeat.
- DEBUG: the detail behind a warning, the stack of a failure, and what happens per
  request. Nothing here is needed to run the feed.

Throttling: a failure that can repeat per message, per call or per tick is logged at WARN
or ERROR the first time and then once in a thousand, with the count so far in the line
(`..., 2000 so far`). The others go to DEBUG, with their stack. Some are limited by time
instead: the first, then at most one a minute.

Secrets: the access token is redacted from the failures the SDK logs, the message and the
stack alike. It travels in a request header of the REST calls, so no URL the SDK logs holds
it, and it is the login of the broker connection, so the broker quotes it in some of its
replies. Those replies are redacted before they are logged, the text and the stack of the
failure alike, and the SDK's own reading of a close reply leaves out what the broker quotes.

Logger names, all under `com.oddin.oddsfeedsdk`. Turn a package to DEBUG to see more of
its area; the classes below are the ones to start with.

| Area | Package | Classes worth knowing |
|---|---|---|
| Feed | `com.oddin.oddsfeedsdk` and `.internal.feed` | `OddsFeed` (open and close), `OpenFeed`, `FeedCore` (startup), `HealthMonitor` (health changes), `Watchdog`, `ConnectionTee`, `RecoveryTee` (client listeners that throw) |
| Broker connection | `.internal.amqp` | `AmqpTransport` (connection, reopening, listeners), also the name of the AMQP client's own reports |
| Recovery | `.internal.recovery` | `RecoveryMachine` (each recovery, producer up and down, the safety net), `RecoveryActor`, `EventRecoveryStatuses` |
| Delivery | `.internal.dispatch` and `.internal.events` | `SessionDispatcher` (a session's messages and callbacks), `AliveDispatcher`, `EventsDispatcher` (global callbacks) |
| REST | `.internal.rest` | `RestTransport` (the API call log) |
| Messages and names | `.internal.message` | `MessageFactory`, `MarketNames`, `Naming` |
| Entities and catalogs | `.internal.entity` and `.internal.descriptions` | `Entities`, `MatchCaches` (the eager preload), `SportsInfo`, `Strategy` (what the exception strategy turns into null) |
| Producers | `.internal.producer` | `Producers` |
| Replay | `.internal.replay` | `Replay` |

To see the connection and the recoveries as they go, set `com.oddin.oddsfeedsdk.internal.amqp`
and `com.oddin.oddsfeedsdk.internal.recovery` to DEBUG. To see each REST call, turn on API
call logging with `setApiCallLogging(true)`: one INFO line per attempt.

The system test `QuietTrafficScenarioIT` stands in for the hour: it sends a few hundred
messages over three matches and checks that what is logged at INFO or above is a state
change from a short list, and nothing else. The real hour on the test environment is a
check of the first release candidate (section 7).

---

## 5. New in 1.0

Additive only, and every addition is on the differences list (section 3). All of it
exists in the Go SDK already. Everything listed here is on `next` (tickets 27 and 28 are
done); what stays out on purpose is named where it stays out.

- Entities: the ids of a tournament's competitors and of a sport's tournaments
  (`Tournament.getCompetitorIds`, `Sport.getTournamentIds`), read from what the views
  already hold, without loading the competitors or building the tournaments, and a
  tournament's icon path (`Tournament.getIconPath`, from the tournament info, the same in
  every locale). Not on the list, and not planned: `Category` on teams and tournaments,
  reference ids on matches and tournaments, `Statistics` on match status and a
  settlement's void reason id and parameters. The Go SDK decodes them, but the producer
  sends none of them and the schema declares none (its notes record each as not sent):
  `category` is an empty placeholder, the void reason id and parameters are sent on a
  bet cancel's markets only, where 1.0 reads them (`MarketCancel`), and statistics and
  reference ids are not there at all. Should the producer start sending one, the schema
  declares it and it comes as a 1.x addition. Also left out, by decision of 2026-10-19: the Go
  SDK's name and abbreviation maps (the names of a match or other sport event, and the
  abbreviations of a tournament and of a sport, each as a map by locale) and the per-locale extra
  info of a match. 0.0.x has `getName(Locale)`, `getAbbreviation(Locale)` and one extra-info map
  (`Match.getExtraInfo`) and nothing more, so no client's code depends on them, and a map would
  have to say "the locales loaded so far", which changes with traffic. If clients ask for them
  they can come in a 1.x minor, as `default` methods.
- Getters: recovery status by request id, `getHealth()` (its transport counts the channel
  refusals, the broker's refusals to open a channel again on a live connection), a single sport, tournament and
  player by id on `SportsInfoManager` (`getSport`, `getTournament`, and `getPlayer` without
  a locale next to 0.0.x's with one), the active producers in a scope
  (`ProducerManager.getActiveProducersInScope`, the Go SDK's `ProducersInScope`), a
  producer's status as `onProducerStatusChange` last told it (`getProducerStatus`), and the
  replay player's status (`ReplayManager.getReplayStatus`).
- Cache control: a clear per entity type, `clearPlayer` and `clearSport` next to 0.0.x's
  clears of a match, a tournament and a competitor; a match's clear takes its fixture with
  it, and its live status is the feed's to write, so neither has a clear of its own. The
  void reasons reload with `MarketDescriptionManager.reloadMarketVoidReasons`.
- Configuration: HTTP timeout, startup deadline, prefetch, maximum message size, REST
  concurrency limit, the broker connection's own TLS context (`setMessagingSslContext`,
  for a truststore of the client's own or a proxy that inspects TLS), max inactivity, max
  recovery time, stale-message limit and window, exchange names, shutdown timeout, API
  call logging (each attempt at INFO; `onApiCall` hears them either way), the health
  thresholds (catalog stale limit, callback and queue stall limits, watchdog interval,
  connection down limit), the default locale, preload locales (whose catalogs `open()`
  starts loading in the background) and the eager entity preload for messages.
- Events on the global listener, all as `default` methods: connection state changes
  (`onConnectionStateChange`), health events (`onHealthEvent`), listener and pipeline
  exceptions (`onCallbackFailure`), fatal errors (`onFatalError`), the safety net's resets
  and refused requests (`onSafetyNetEvent`), sessions lagging and catching up
  (`onSessionLagChange`), API call events with method, URL, status and latency
  (`onApiCall`), and every change of a producer's status with the cause named
  (`onProducerCauseChange`, `ProducerStatusCause`), a change of the cause alone included.
- Raw data: `default` methods on the extended listener delivering raw XML bytes for
  feed messages and REST responses, and raw-string getters next to enum getters in the
  generated XML classes.
- Telemetry: the SDK reports its version the way the Go SDK does - an HTTP `User-Agent`
  of `oddin-javasdk/<version> (java <version>)` and an `X-Oddin-SDK-Version` header on
  every API call, and `SDK_version` next to today's `SDK=java` in the AMQP client
  properties. The version is the jar's own, written in by the build; a snapshot reports
  it marked `-dev` (`1.0.1-SNAPSHOT` as `1.0.1-dev`), as the Go SDK marks a build that
  is not a release. `OddsFeed.getSdkVersion()` returns it so a client can log what it
  runs.

Things the Java SDK has and Go does not stay: multi-session with priority interests,
`setSpecificEventsOnly`, raw API data callback. Two readings differ from Go on purpose: a
`liveodds` the API leaves out reads as `AVAILABLE`, as in 0.0.x, where Go reads it as not
available; and a lost alive channel counts against no producer (section 4, Delivery).

---

## 6. Two release lines

| Line | Branch | Stack | Versions | Until |
|---|---|---|---|---|
| Old | `release/0.x` | Kotlin, Gradle, Java 8 | 0.0.57+ | 31 March 2027 |
| New | `next`, then `main` | Java 25, Maven | 1.0.0-rc.N, then 1.0.0 | ongoing |

What the old line gets until its end date: critical fixes, additive wire fields (a new
XML attribute the producers start sending), and one retrofit in ticket 7: the vendored
schema fixtures and a small golden decode test over them, so the old line has a pin and
a test like the new one. Its last release, when support ends, is the relocation POM of
ticket 33.

How the two lines stay in sync on the wire: each line vendors the schema at a pinned
commit and decodes its fixtures in its own tests. A scheduled CI job in this repo reads
both pins and fails when either lags the schema repo's head by more than seven days
**for additive changes**; a shape change to a public class is never taken into 1.x
(difference 4) and the job reports it as "needs a decision" instead of failing. A
schema bump is one PR per line, opened together. The PR template checkbox is a
reminder; the drift job is the control, and the person who opens the schema PR owns
the two SDK PRs.

Timeline we communicated: test builds in October and November 2026, release at the
end of November or beginning of December 2026.

---

## 7. Tests

Three layers. No ticket is done without its tests.

1. **System tests, written first.** Black-box, public API only. A real RabbitMQ in a
   container with a publisher that replays fixture messages, and a fake REST server
   serving the schema fixtures. They assert what a client can observe: which callbacks
   fire, entity values, locale handling, invalidation on fixture change, producer down
   and recovery, reconnect, REST outage, REST down at startup, authentication failure,
   stale feed, a callback that throws, replay, exception strategy. They run against
   0.0.59 first, so we know each test actually tests something. Then they run against
   1.0.0. Same tests, one version property. CI runs the suite twice, once per version,
   and the 1.0.0 run asserts the loaded jar's version through the telemetry getter, so
   a misconfigured build can never pass by silently testing the downloaded old jar.
   0.0.59 comes from its public GitHub release, checked against pinned digests, with no
   token.
2. **Unit and concurrency tests with every ticket.** Golden decode tests for every
   message and endpoint from the schema fixtures. Cache tests: expiry per locale,
   eviction, locale marks, clear, tombstones and generations, authoritative versus
   fill-only writes including a cleared field that must not resurrect, watermark
   ordering with the long-lived watermark record, REST fallback after feed silence,
   stale-message write suppression. The dependency test for caches versus loaders and
   the latch-based deadlock tests. Recovery actor tests: per-session checkpoints,
   alive-based advance, snapshot exemption, coalescing, caps and re-arm, session open
   and close. Safety-net tests: per-producer offsets, stale offset, request-before-
   reset ordering, rejected request, cap arbitration. Channel-epoch tests for every
   replacement path including the drain-before-register order. Events dispatcher
   bounds. `open()` rollback on partial failure. Lifecycle races.
3. **Soak and client tests last.** Real test broker, replay of recorded traffic,
   release candidates to clients who volunteered.

Two rules from the last hotfix review: a regression test must be shown to fail on
the broken code before it counts, and a test that cannot fail is a bug.

### Soak scenarios

The soak is the third layer run against the real thing: the first release candidate on the
test environment (section 10, ticket 34), with a client that logs at INFO, persists its
resume points and exports `getHealth().counters()`. The fakes prove much of each
scenario's logic, as the last entry of its row says, and some of it nowhere yet; the soak
proves the timings, and the real broker and API. The client cannot restart the environment's
broker or API, so the faults are made between them, with a TCP proxy (such as Toxiproxy) in
front of the broker and of the REST host: cutting or resetting a connection, refusing or
stalling one. A TCP proxy does not read AMQP or HTTP, so it cannot refuse a login, drop one
producer's messages out of the broker's one connection, or answer 5xx: the 5xx comes from a
small HTTP stub that the feed's REST host (`selectEnvironment`) points to, which forwards or
answers as the scenario says, and the other two are made as the table says. Where the
environment's operators can restart a broker node or a producer, that is the better fault.

Watched in every scenario: the `onHealthEvent` stream, and `getHealth()` every minute
(states, `session.N.queue_depth`, lag, the recovery and safety-net counters, dropped
side-loads, stale catalogs); `onProducerStatusChange` and `onProducerCauseChange`; the
recovery requests, from the API call log (`setApiCallLogging`); the log at INFO and above;
heap after a full collection, live threads (the `oddsfeed-*` ones and the virtual
threads) and file descriptors. A failure in every scenario: any part `STALLED`, but the
slow client's own, which its row asks for and which must be `HEALTHY` again after the cure;
a heap after collection, a thread count or a descriptor count that climbs from one run of a
scenario to the next; a WARN or ERROR line that repeats past the throttle (section 4,
logging); a message lost with no counter and no event; a producer that is down with no
attempt to bring it back pending. A lost message cannot be seen by the SDK alone, as every
reading is its own: the soak client also runs a bare AMQP consumer that is not the SDK, with
its own exclusive queue bound as a session's is, counts what it receives per producer and
routing key, and the totals of the same hours are reconciled with `session.N.handled` and the
callbacks; a difference has to be explained by a counter or an event of those hours, and what
a replaced channel or queue held has to be covered by a recovery.

| Scenario | How | Watched, beyond the above | Passes when | Fails when |
|---|---|---|---|---|
| Broker restart | Reset every connection through the proxy, or restart the broker node. Four runs: back within seconds; held down for two minutes; the login refused for 30 s and then accepted; the login refused for three minutes. A refusal is the broker's own answer, so the last two need the environment's operators to change the credential or the virtual host's permissions on the broker side; a proxy cannot make one | the connection part; `onConnectionDown`; reconnects; per producer the cause (connection lost, channel lost) and the recovery after it; any request sent while the connection is down | the connection is `DEGRADED` only after 60 s down (`setConnectionDownLimit`) and `HEALTHY` as soon as it is up; no recovery request while it is down or before the transport's first up; each producer is up again once its recovery completes, and the recovery is asked for after the connection is back and not before; it waits five minutes at most for its `snapshot_complete`, counted from the request and again from each message of its snapshot (section 4, recovery); refusals that go on for 30 s are ridden out, with no fatal error; refusals that go on past a minute end the reconnecting with a fatal error that carries the broker's reason, how many refusals and over how long (section 4, connection: three at least, going on for a whole minute with no connection between; the backoff is 1 s doubling to 30 s, with jitter, so the first refusal past the minute comes within two minutes, which is why that run lasts three, and a refusal of about a minute proves nothing) | a producer down with no recovery asked for, or with a snapshot that has not moved for five minutes and is not given up; a request sent while down; a fatal error after a restart the broker came back from, or after refusals that went on for 30 s; no fatal error after three minutes of refusals; a "down" on a normal `close()`. Against the fakes: `ReconnectScenarioIT`; the refusal rule in unit tests only (`RefusalsTest`, `AmqpRefusalTest`) |
| API outage | Stall or refuse the REST host with the feed running: ten minutes, then 70 minutes. In the short one, cut a broker connection once | catalog state and how long it was stale; getter latency in callbacks; recovery requests, re-issues and failures; the API's answers | messages keep arriving and are delivered; a getter returns or fails within `2 × (timeout + 1 s) + 1 s`, so a slow API is no stall (limit 65 s by default); a refused recovery is re-issued from 5 s, doubling, three times in a row, then the producer stays down with the reason, and is asked for again after ten minutes or at the first alive after a gap; a catalog is `DEGRADED` after an hour stale (the 70-minute run) and `HEALTHY` once the API is back; every producer is up within the cool-down after it | `STALLED` for a slow API; a producer down with no attempt pending; a log line per failed call; the preload or the recoveries flooding the API as it returns. Against the fakes: `RestOutageScenarioIT`, `RecoveryRefusalScenarioIT` |
| Producer restart during a recovery | Right after a reconnect, while a recovery is in flight, stop the producer's alives for 30 s to two minutes - at the source, by the environment's operators, or by a filter that reads AMQP and drops by routing key in front of the broker, which has to be built: a TCP proxy sees one connection for every producer's messages and cannot drop one's; once with the alives returning unsubscribed | the producer's cause (no alive, unsubscribed); recoveries issued, given up, re-issued; the cap | the producer is down after 20 s without an alive (`setMaxInactivitySeconds`); the recovery in flight is given up uncounted and the next alive asks for one again (KD-26), or joins it when it comes before the API's answer; the producer is up when its `snapshot_complete` arrives; the cap is not spent by it | two recoveries in flight for one producer; a producer down while its alives flow; another instance's request id taken as its own. Against the fakes: `ProducerRecoveryScenarioIT`, `ProducerStatusScenarioIT` |
| Slow client | One session's callback sleeps 5 s per message; then one callback of 90 s; then the client is cured | that session's queue depth and lag; `onSafetyNetEvent`; resets; `onSessionLagChange`; the other sessions | at 20 s behind, the producer is down without a recovery and comes back (`RETURNED_FROM_INACTIVITY`) when on time; two minutes stale for a minute (`setStaleMessageLimit`, `setStaleMessageWindow`) make the net ask for recoveries, then replace the channel, a minute apart at least, three resets in ten minutes at most; then the session is lagging and the producer stays up; the 90 s callback is `STALLED` after 65 s and healthy when it returns; other sessions are untouched; all of it clears after the cure | a reset before its recoveries were accepted; a fourth reset in ten minutes; a producer down for a lagging session after the cap; another session delayed; the lag not cleared once caught up. Against the fakes: no system test sleeps in a callback. The net: `SafetyNetTest`, and `StaleFeedScenarioIT` for a session far behind (messages stamped late, not a slow callback); the stall: `WatchdogTest` and `OddsFeedWatchdogTest` (a callback that does not return, with short limits; the default of 65 s is pinned in `theWatchdogsLimitsAndTheHealthsAreTheConfigurations`) |
| Close during a recovery | `close()` while a recovery is in flight, at random points, 50 times, a new feed each time; once from a callback | how long `close()` takes; the resume point read after it, twice; callbacks and events after it; threads left; event recovery statuses | `close()` returns within the shutdown timeout (5 s by default); no callback starts after it begins; no producer-up or recovery completion comes from the close; the resume point is the same on both reads and not ahead of what was processed; a feed opened with it recovers from there; every `oddsfeed-*` thread, the keep-alive included, is gone | a hang; a thread left; an event after `close()`; a point that differs between two reads; a recovery cut short reported completed. Against the fakes: no system test closes during a recovery; `ReconnectScenarioIT` shows only that a close is not reported as the connection down. The close's own rules are unit tests (`RecoveryActorTest`, `RecoveryMachineTest`, `SessionDispatcherTest`) |
| Restart with many live matches | Restart the client with `setEagerEntityPreload(true)` and the default REST limit while as many matches as the environment has are live | the API's concurrent requests against the limit; the side-load workers (half the limit, eight by default); dropped and failed side-loads; time until the sessions are caught up; getter latency in callbacks; the safety net | the preload takes no more than half the data permits, so callbacks' own reads stay inside their deadline; the API sees no more concurrent requests than the limit; dropped side-loads are 0, or the burst the queue of 1 000 could not hold, counted and logged; the sessions are caught up within the cold benchmark's budget (section 8, scaled to the environment's size); no safety-net reset comes from the restart | 429s; a session reset by the cold start; a read past its deadline; a failed preload neither logged nor counted. Against the fakes: the cold benchmark (section 8), no system test |
| Long run | Normal traffic for the length below, the scenarios above repeated in it | the whole list above, as trends over days | no trend in heap, threads, descriptors or queue depth; the counters of a failure or a degradation (dropped, failed, timed out, abandoned, refused, unparsable, oversized, overflows, epoch discards, callback failures) stay flat outside a fault, and in one only those it names move, while the counters of traffic (`alives.handled`, `session.N.handled`, `recovery.completed`) climb with it; every fault healed on its own | a leak; a failure counter climbing with no fault made; a producer never up; a part still `DEGRADED` after its cause ended |

**Soak length: 72 hours**, agreed by the owner on 2026-10-19: on the test environment, a weekend day
among them. The longest timer the scenarios depend on is the catalog stale limit of one
hour, so the API outage needs 70 minutes of its own, and the ten-minute cool-downs have to
be seen to expire; together the scenarios take most of a working day. What a day cannot
show is the daily cycle of matches coming and going, cache expiry and eviction across it,
and weekend traffic, so after the scenarios the feed runs at least two more days with
nothing done to it but one fault a day, one of them on the weekend. Shorter would miss a
slow leak; a week would show little the second and third day have not. What the first
candidate shows may still change it.

**Two checks that move to the first release candidate**, because they need the real thing:

1. **One hour of normal traffic logs only state changes at INFO.** On the test environment,
   a feed with several sessions and the default locale, no fault, one hour: what is logged
   at INFO or above is a state change from section 4's list (opened, a producer up or
   down, a recovery, a session caught up, a part healthy again), with the API call log off,
   and nothing per message. `QuietTrafficScenarioIT` stands in until then.
2. **The first release candidate is the first signed publish to Maven Central.** Check on
   `https://repo1.maven.org/maven2/gg/oddin/oddsfeed/odds-feed/<version>/` and on
   `odds-feed-parent` beside it (`RELEASING.md`, "Every release", step 6):
   - the jar, the sources jar, the javadoc jar and the POM are there, each with a `.asc`
     and its checksums;
   - every `.asc` verifies against the release key fetched from `keyserver.ubuntu.com` or
     `keys.openpgp.org` by the fingerprint in the repository variable `RELEASE_KEY_FINGERPRINT`
     (the one the release's key check uses), not from the build; a key with another fingerprint
     is a substituted one and fails the check; the key has not expired. Until the key exists
     this cannot be met;
   - the POM is the flattened one: the right coordinates, no `${revision}` left, the
     licence, SCM and developer entries Central requires, and the dependencies the module
     needs and no others;
   - the jar's manifest has `Automatic-Module-Name: gg.oddin.oddsfeed`;
   - a clean project with an empty local repository and Central as its only repository
     resolves the candidate under Maven and under Gradle, and the examples compile
     against it;
   - the GitHub Release has the jar, the POM and their signatures, byte for byte those on
     Central (compare digests).

   The relocation POM on the old coordinates is not part of this: it is the old line's last
   act, published when 0.0.x support ends on 31 March 2027 (ticket 33).

---

## 8. Performance

Performance is a requirement, not a follow-up.

- A benchmark harness in the repo: generated odds changes shaped like a live match's,
  run through decode, cache and entity build, with JMH. Budgets per message for time and
  allocation. Runs in CI as a regression check. It has a **cold scenario** as well: a
  restart-shaped run where every entity is a miss and the eager preload is on, against the
  fake REST server with realistic latency, with a budget on time-to-caught-up. The
  cold path is the one that decides whether a client trips the safety net after a
  restart.
- Rules for the hot path: no copying of cached descriptions to read one field, one
  lookup per market and locale per message, names resolved lazily on first
  `getName()`, no `String.format` or boxing in loops.
- Cold path: parallel bounded fan-out for competitors and players, single-flight,
  under the data permit pool.
- Outage: serve stale data while a refresh is failing, back off per locale. Never
  collapse to one message per HTTP timeout. The catalogs do. The entity loads have no
  backoff, by decision (2026-10-15): as in 0.0.x and the Go SDK, a failed load is asked
  for again by the next read. Concurrent readers of a key already share one request,
  and every call is bounded by its deadline. A per-key backoff was tried and dropped:
  after an outage it kept entities refused for up to its cap once the API was back.
- JAXB's unmarshal cost, measured early: on the JDK's own parser three times a
  hand-written StAX reader's, on Woodstox about a sixth slower than it. The decoder reads with
  Woodstox, and the generated classes stay without a reader to maintain.

---

## 9. Build and release

- Root `pom.xml`, modules `odds-feed` (published), `system-tests`, and `test-fakes`, the
  fake REST API and the fake feed that both run against (not published; it adds no AMQP
  client or XML binding, so the system tests use it with 0.0.x too). Maven wrapper
  checked in. The old `examples` and the generated usage are compiled by a test in
  `odds-feed`, not by modules of their own.
- The schema is vendored: a copy of the schema repo's XSDs and fixtures lives in this
  repo, with the source commit recorded next to it and a script that refreshes the
  copy. The build never reaches out to another repository. Old releases stay
  rebuildable. The drift job from section 6 watches the pin.
- JDK 25 toolchain, JaCoCo, Surefire and Failsafe, Enforcer, the compatibility checks,
  the differences list, XML generation from the vendored schema.
- Version from the git tag. GitHub Actions on `v1*` tags on `main` - the merge commit of a
  PR merged into it, as `main` alone requires review; never from `next`: build,
  compatibility checks, system tests against both versions, then a pipeline step that
  queries the target registry and fails if the version already exists, then sign and
  publish. Every release, candidates included, waits for a second person to approve the
  upload, because Central is irreversible and the workflow a tag runs is the tagged
  commit's own.
  The GitHub Release with the jar and POM is created afterwards. Signing key and
  Central credentials live in the secrets of an environment only `v1.*` tags can use,
  and only once its reviewers approve the job.
  `RELEASING.md` has the checklist.
- The jar names the automatic module `gg.oddin.oddsfeed` in its manifest, from the first
  release on: naming it later would rename the module under the clients that require it.
- The new line publishes `gg.oddin.oddsfeed:odds-feed` to Maven Central only. The old
  line keeps publishing `com.oddin.oddsfeed:odds-feed` to GitHub Packages only, from
  `release/0.x`. Each line's pre-release check queries its own registry. Never two
  release tags on one commit.

---

## 10. Work breakdown

Small tickets, one PR each, half a day to three days. Each has a visible result. Order
matters where it says so, the rest can run in parallel. Tickets added after the plan
review of 2026-10-03 take the next free number and sit under the ticket they follow.

### Phase 0 – Foundation

1. This design doc approved.
2. `next` branch, root POM, Maven wrapper, JDK 25, empty CI green. Only the
   `system-tests` module exists. It depends on the SDK by coordinate, so switching
   between the old and the new SDK is a group id and a version property - the group id
   changes with 1.0, so one property is not enough.
3. Vendor the schema: copy XSDs and fixtures into the repo with the source commit
   recorded and a refresh script. Everything below reads them.
4. Fake REST server for tests, serving the schema fixtures.
5. Fake feed for tests: RabbitMQ in a container plus a publisher that replays fixture
   messages. An in-process fake is not possible, the old SDK opens a real connection.
6. First system tests green against 0.0.56: open, receive an odds change, read a
   match, close. Includes the JAXB runtime the old jar needs on a modern JDK.
7. Cut `release/0.x` once 0.0.56 is tagged, keep its Java 8 CI green, vendor the
   fixtures there with a golden decode test, add the dual-line checkbox to the PR
   template and the schema drift job.

   37. Remove the old Kotlin release workflow from `next`, which would publish
       `library/` on a `v*` tag, and add the PR template to `main`, where GitHub reads
       it. Before any tag.

### Phase 1 – Contract and wire

8. System tests, batch two: every feed message type and the callbacks it triggers.
9. System tests, batch three: locales, invalidation, producer down and recovery,
   reconnect, REST outage, REST down at startup, authentication failure, throwing
   callback, replay, exception strategy, stale feed. Known differences from 0.0.x are
   listed, not fixed.
10. `odds-feed` module with the public entity and message types as source-compatible
    declarations, no behaviour.
11. The rest of the public API as declarations: managers, sessions, listeners,
    configuration builder. Old `examples` compile. The generated usage compiles
    against both versions. The comparison scoped by reachability, with the
    differences list. Compatibility checks run in CI.
12. Generated feed models with name-preserving bindings, decoder hardening, raw-string
    getters, plus golden decode tests. One PR per message family. Lists the types
    whose shape changed.

    38. JAXB on Woodstox for the feed and REST decoders, with the decoder's limits.
        Done.
13. Generated REST models with name-preserving bindings plus golden tests. One PR per
    endpoint family. Same list.
14. HTTP client: all endpoints, four permit pools, one deadline per call covering
    permit, call and retries, retry for idempotent calls only, error mapping including
    permanent failures with the fatal event, 429 handling, startup deadline, API call
    events. Also the version headers and getter from ticket 29, and the HTTP timeout,
    REST concurrency limit and startup timeout options. The client reports to an
    internal events interface; ticket 22 connects it to the dispatcher and adds the
    public listener methods.
15. Benchmark harness: corpus, JMH skeleton, warm scenario, CI budget check. The cold
    scenario is ticket 40.

### Phase 2 – Core

Each ticket includes its concurrency and deadlock tests. System tests turn green
group by group.

16. Cache and loader infrastructure: caches as non-blocking maps, loaders with
    single-flight and deadlines, the cache-versus-loader dependency test, bounds and
    per-locale ages, generations with tombstones, authoritative versus fill-only
    writes with the authoritatively-written marks and the per-field endpoint table,
    locale marks, clear, the live state with its feed watermarks, REST fallback after
    feed silence, stale-message write suppression, and the latch-based deadlock tests.
17. Entity caches: match and fixture, and the match's live state written from the
    summary.
18. Entity caches: competitor, player, tournament, sport.
19. Catalog caches: market descriptions, void reasons, match status descriptions,
    provenance-aware refresh with stale serving.
20. Entity façades and factories with parallel multi-locale loading and the
    partial-failure rule. Also the catalog façades and `SportsInfoManager`, the
    side-loads, the eager entity preload for messages, and the cross-cache deadlock
    tests. Ticket 28 wired the preload to the message path, with its option.

    39. Player underage on the 1.0 line, as merged on `release/0.x`, and the API
        compatibility baseline moved to each new 0.x release.
21. AMQP layer: connection, reconnect with permanent versus transient classification
    and the refusal rule, connection events, one channel per session, prefetch
    validation, maximum message size, raw hand-off into bounded session queues, the
    channel replacement sequence with epochs and `SessionTransport.reset()`, the
    SDK-owned alive consumer, unparsable disposition, `open()` rollback. Also `SDK_version`
    in the client properties (ticket 29), the prefetch and maximum message size options,
    and the fake feed moved into `test-fakes` for the SDK's own tests.
22. Session dispatchers: decode, build, cache write, callback, ack, the one failure
    policy for every step; message factory, markets and outcomes; fixture-change
    deduplication with today's key; the events dispatcher with its two bounded queues.
    Also the alive dispatcher and the facts the dispatchers post to the recovery actor,
    the remaining public listener methods, KD-5 and KD-6, the public enums' `Companion`
    members back, field-level golden tests for the feed messages, and the warm-path
    benchmark steps for cache write and entity build.

    40. Cold-start benchmark: the cold scenario ticket 15 left for later, with a CI
        budget.
23. Producer manager and whoami.
24. Recovery actor: single owner thread, random-seeded ids with reseed, per-producer-
    per-session checkpoints with alive-based advance and snapshot exemption, window
    clamping, per-interest completion, session open and close, coalescing, re-issue
    with cap and re-arm, the safety net with per-producer offsets, stale-offset
    disable, pause during recovery, request-before-reset, its own cap and the lagging
    state, producer-status reasons. The state machine and the actor with the narrow
    interfaces the dispatchers, the alive dispatcher, the transport and the façade call;
    tickets 22 and 26 connect them. The transport reports a session channel it reopened on
    its own (`ChannelEvents.reopened()`).
25. Replay manager.
    Also `getReplayList`, built through ticket 20's factory.

    41. Transport and recovery hardening: the findings left open on the approved PRs
        of tickets 21 and 24. The starts counted back from the initial snapshot
        interval stay on the SDK's clock, by decision (2026-10-15): 0.0.x counts them
        there too, the two clocks differ only when the client's clock is off, and the
        request's start is capped at the producer's now.
26. `OddsFeed` façade, sessions, builder, one-shot lifecycle with all-or-nothing
    `open()`, watchdog over every thread group, `getHealth()` with the full counter
    list. Also the recovery actor's wiring and its listener methods, `RecoveryManager`,
    resuming from the oldest checkpoint, and the system tests against 1.0 in CI. The
    actor starts before `transport.open()`, and its `start()` replaces any gap opened
    before it, so a loss told before start cannot replace the client's recovery point.
    At shutdown it tells the actor `closing()` before closing any session.
    Once the system tests run against 1.0, the KD-12 and KD-27 scenarios also check the
    second recovery's `after` and the producer going down and back up.
    The recovery runs in the feed, and the whole system-test suite passes against 1.0 as
    well as 0.0.59, in CI; `getHealth()` and the watchdog are in.

    42. Concurrency stress suite on the assembled SDK, in CI.

### Phase 3 – Parity and polish

27. Field parity with the Go SDK, in small groups. Done: section 5 lists what came and what stays
    out and why.
28. Option and method parity: the options and methods of section 5 the release candidate
    does not have. Includes the safety-net, inactivity, recovery-time and
    shutdown-timeout options, the eager entity preload for messages with its option, the
    locale preload, and the test that pins KD-17. The maximum recovery time keeps
    0.0.x's default of 360 minutes. Done: the
    maximum inactivity, maximum recovery time, shutdown timeout, stale-message limit and
    window, exchange names and API call logging options, each checked by the builder, and
    KD-17's test, which sets the safety net's options on 1.0; the health thresholds -
    catalog stale limit, callback and queue stall limits, watchdog interval - and the
    connection down limit, which makes a connection down for longer than 60 s `DEGRADED`;
    a sport, a tournament and a player by id, and the clears of a player and of a sport;
    the active producers in a scope, a producer's status, the replay status and the reload of
    the void reasons; the default locale, the preload locales and the eager entity preload.
29. Telemetry, done: the REST headers and the public version getter with ticket 14,
    `SDK_version` in the broker connection's client properties with ticket 21.
30. Logging cleanup, done: levels and throttling, one wording, and the logger names in
    section 4, Logging. Noisy logs were a client complaint.
31. README, examples, release notes and upgrade guide, integration guide with the
    onboarding checklist (distinct node ids, the operator's queue limit above prefetch,
    the per-session memory budget with the AMQP client's own buffer) and resuming after
    a restart, FAQ update. The upgrade guide takes its list from KNOWN-DIFFERENCES.md,
    and says what a start with the API down costs (KD-36). The replay guide says that a replay run again within the
    hour has its fixture changes dropped as duplicates of the run before, by the
    fixture-change deduplication, in both lines.
32. Sweep the Go and .NET SDK history for fixes to port, from Go SDK 1.4.0 (commit
    `f0e8728`) and the .NET SDK of the same date. Again before every release.

### Phase 4 – Release

33. Maven Central pipeline: claim the `gg.oddin` namespace, signing, registry check
    step, tag-driven publish from `main` with manual approval. Also a last 0.0.x
    version whose POM only relocates to the new coordinates, so a Maven build still on
    the old ones is told by a warning; Gradle follows a relocation without showing its
    message. It is published when 0.0.x support ends on 31 March 2027, after the last
    0.0.x fix, since a fix released after it would undo it for the clients it moved.
    Before its tag, someone confirms by hand that the remaining Java 8 clients have
    pinned a 0.0.x version: a Java 8 build on `0.0.+`, or one that Renovate or
    Dependabot bumps, takes it as a patch and gets the Java 25 jar. The POM names a
    fixed target version, so the publish job refuses it unless that version is on Maven
    Central and is a final release. Also the GitHub Release, a coverage threshold, and
    the pre-release registry check on both lines.

    43. Performance validation before the release candidate: the warm and cold
        benchmarks and a sustained-load run on the test environment, against the
        budgets.
    44. Make `next` the main line: remove `library/` and Gradle; CI, the schema drift
        job, the rulesets and the README for the new `main`. Before the first release
        candidate.
34. First release candidate, soak on the test environment, candidates to clients.
35. Fix round.
36. End-of-life notice for 0.0.x sent to all clients, 1.0.0 released. The notice tells
    Java 8 clients that the old line gets critical fixes until 31 March 2027; until then,
    the notice and the README tell clients about the move. Shortly before that date, a
    reminder asks Java 8 clients to pin the last 0.0.x version, and the relocation of
    ticket 33 follows once they have. Pinning earlier would cost them the fixes between.

Critical path: 3 to 6, then 10, then 16, then 17 to 22, then 24, then 26, then 43 and
44, then 34; 37 before any tag. The benchmark, the Central pipeline and the
`release/0.x` cut fit into gaps.

---

## 11. Risks

- **Timeline.** Two weeks went to the hotfix already. If the schedule slips, the
  release candidate goes out later, not with fewer tests.
- **Silent behaviour differences.** Clients depend on things we do not know about.
  The system tests against 0.0.59 are our best defence. Release candidates to clients
  are the second.
- **Generated names.** Keeping the old class names through bindings works where the
  schema type maps one to one. Where the aligned schema changed a shape, the class
  differs and a client cast can fail at runtime. The list of such types goes into the
  release notes; the compatibility check cannot see casts.
- **Transitive binary breaks.** Not promised, not detectable by our gate. Mitigated by
  the major version and by the fact that no intermediate library is known.
- **Both SDKs on one classpath.** The two lines now have different group ids but the
  same packages, so a client who adds the new dependency without removing the old one
  gets two jars carrying the same classes. Maven sees two unrelated artifacts and says
  nothing; which one wins is classpath order. The relocation POM on the old line, the
  release notes and a ready-made dependency ban in the upgrade guide are the answer;
  Gradle follows the relocation too, but shows no message. The upside of the split:
  nothing offers 1.0.0 to a Java 8 client as a version bump, except the relocation,
  which is why it waits until 0.0.x support ends (ticket 33).
- **Shared node ids.** Two instances configured with the same node id can confuse each
  other's recoveries. The SDK cannot detect it. Documentation and the onboarding
  checklist carry the rule.
- **Operator queue limit below prefetch.** Silent drop-head loss the SDK cannot see.
  The onboarding checklist carries the rule.
- **JAXB speed.** Measured in Phase 1 and settled: the JDK's StAX parser was the cost, and
  JAXB on Woodstox is about a sixth slower than a hand-written StAX reader (`benchmarks/README.md`).
- **Two Java 8 clients.** They cannot use 1.0. The old line covers them until the
  end date. Anything beyond that is a business decision, not a technical one.
- **Two lines to maintain.** Every wire change is done twice until the old line ends.

---

## 12. Open questions

1. Which clients test the release candidates? Needs an answer from customer success.

Resolved since the first draft: the priority-split session interests keep today's
semantics, since they are public API (section 3). The feed defines `bet_stop`
(`bet_stop.xsd` in the schema), so the type is a message the feed sends. The generated
XML classes are public and keep their old names (section 3, difference 4).
JAXB or StAX for decoding (2026-10-01): JAXB, on
Woodstox, for the feed and REST alike. A typical live odds change of 150 markets took
0.9 ms with JAXB on the JDK's own parser and 0.3 ms with a hand-written StAX reader; on
Woodstox, JAXB takes 0.32 ms, so there is no parsing code to keep in step with the
schema. Woodstox is Apache 2.0, its one dependency BSD.
The maximum recovery time's default (2026-10-06): it stays 0.0.x's 360 minutes, and
ticket 28 makes it settable. With the five-minute deadline it bounds only a recovery
that keeps coming, and an event recovery the API has not answered.
The relocation of the old coordinates (2026-10-06): published when 0.0.x support ends,
after the last 0.0.x fix, once someone has confirmed by hand that the remaining Java 8
clients have pinned a version, and only to a final release that is on Maven Central
(ticket 33).

---

## 13. Review log

- First draft 2026-09-18.
- 2026-09-21: review comment on the recovery-snapshot throughput promise. Replaced by
  the stale-message safety net.
- 2026-09-21: first automated three-reviewer design review, 47 findings kept. Ack after
  callback, blocking rule widened, write rule rewritten, generation counters, feed
  ordering, time-seeded recovery ids, recovery caps, safety-net clock correction,
  global REST semaphore, permanent-failure detection, decoder hardening, watchdog
  scope, public API by reachability, generated classes keep names, real
  source-compatibility gate, vendored schema, release gates.
- 2026-09-21: second automated review of the revised text, 59 findings kept. Raw
  hand-off off the consumer executor, SDK-owned alive consumer, named thread groups,
  throwing callbacks acked, channel epochs, two REST pools, authoritative versus
  fill-only writes, no clock comparison, per-producer watermarks, expire after write,
  provenance-aware catalogs, bounded generation map, per-session checkpoints, random
  ids, re-arming cap, safety net suspended during its snapshot and yielding after the
  cap, transient broker limits, raw-string getters, health counters, system tests on
  both versions, drift job, difference 5, reachability test.
- 2026-09-22: third automated review, 52 findings kept, none critical. The recovery
  actor now owns all producer and recovery state; the events dispatcher has two bounded
  queues; caches are non-blocking maps and loaders do the waiting, enforced by a
  dependency test; one deadline per REST call covers permit, call and retries; three
  permit pools; startup deadline; maximum message size and the per-session memory
  budget; the channel replacement sequence drains before it registers; one failure
  policy for every pipeline step; authoritatively-written marks stop cleared fields
  from resurrecting; tombstones keep generations inside the cache; the feed watermark
  outlives the status entry; stale delayed feed messages do not write after REST took
  over; per-locale freshness; dedup key restored to today's; replay next to live no
  longer writes live state (difference 7); checkpoints advance on alives when the
  session is drained and never on snapshots; sessions leave and join the checkpoint
  sets; requests coalesce per producer; the cap re-arms on a cool-down; the safety net
  uses per-producer offsets, pauses on every session during a recovery, requests
  before it resets, and marks a session lagging instead of a producer down; the
  additions list; Jakarta annotations as difference 6; the old line gets vendored
  fixtures; the drift job distinguishes additive from shape changes; `open()` is all
  or nothing; auth refusals need three strikes; watchdog covers every thread group and
  every internal wait has a deadline; the benchmark has a cold scenario; counters for
  everything that discards data.
- 2026-09-28, from the system tests: 0.0.x refuses a replay session next to any other
  session on one `OddsFeed`, because the replay session's interest is `ALL`. So the
  mixed setup behind difference 7, replayed state reaching live caches, never existed,
  and the promise that a replay session can sit next to live sessions was wrong.
  Difference 7 and the mixed-instance rule of ticket 25 are withdrawn; the combination
  stays refused, as today.
- 2026-09-29, ticket 11 checked against what tickets 10 and 12 had built: the
  class-file comparison and its differences list already did the reachability test's
  job, so the comparison is scoped by reachability instead of a second test. The
  curated `api-usage` module became generated usage, which covers every signature
  without upkeep; the advisory jar diff is dropped. Telemetry spelled out after the
  Go SDK: version in `User-Agent`, `X-Oddin-SDK-Version` and the AMQP client
  properties.
- 2026-09-30, ticket 14 checked against the Go SDK's client: its retry policy is taken
  as it is (three attempts, idempotency per call, 429 always retried), plus `Retry-After`,
  which the design asks for and the Go SDK ignores. The version telemetry moved into
  ticket 14 so that no API call goes out without it: `User-Agent`
  `oddin-javasdk/<version> (java <runtime version>)`, a snapshot build reported as
  `-dev`, and `OddsFeed.getSdkVersion()`. The fake REST API moved into a module of its
  own, since the SDK's tests cannot depend on the system tests, and learned replies in
  turn, delays and headers.
- 2026-10-01, ticket 19 checked against 0.0.x and the Go SDK: catalogs refresh an hour
  after their fetch.
  A dynamic market variant is always fetched from its own endpoint, also when the list
  carries a row for it. A miss refetches the list when it is a minute old instead of on
  every miss as 0.0.x did. A read with nothing to serve fails at once while its key backs off, which
  keeps a cold outage from costing one HTTP timeout per message.
- 2026-10-01, ticket 24 checked against 0.0.x, the Go SDK and the system tests: the
  checkpoint advances on the alives in the session's own queue instead of on a look into
  it; what a recovery covers is kept as gaps, so a producer that stops sending recovers
  from its last subscribed alive while a lost queue recovers from its session's
  checkpoint; a recovery goes out at an alive, as in 0.0.x, and one whose snapshot went
  with a lost queue is given up; the safety net recovers every producer of the session
  it resets, and a `snapshot_complete` before the reset cancels it; producer status keeps
  0.0.x's reasons, with 0.0.x's processing-delay down, and names a cause next to them.
- 2026-10-03: the 24-hour maximum staleness of the catalogs is dropped. A catalog entry
  is served stale for as long as refreshes fail, as 0.0.x did; the health reports how
  long, for `getHealth()` to show as degraded.
- 2026-10-04, ticket 32: the Go SDK swept from 1.4.0 (`f0e8728`) to `a9d29d9`, the .NET
  SDK from 2026-09-07 to `66fcfca`. One fix applied to merged code: the transport tells a
  session when the broker takes its channel and when a new one is bound, which recovery
  after a lost channel needs. Every other fix is in place already or named for the ticket
  that carries it; the table is in the pull request. The next sweep starts from these
  two commits.
- 2026-10-05, tickets 24 and 41, after the runtime design review: a recovery the
  producer lost is given up at its producer gap and asked for again at the next alive,
  instead of being waited out for the maximum recovery time; a producer's recovery
  waits five minutes at most for its `snapshot_complete` once nothing more of it comes,
  after the Go SDK's unmerged fix for a lost one; the actor asks for nothing before the
  transport's first up, nor for a session's producers between its lost channel and the
  new one. The remaining findings on the approved pull requests of tickets 21 and 24
  are each fixed or answered in the pull request.
- 2026-10-06, two decisions: the relocation POM on the old line is published after the
  end-of-life notice, once the remaining Java 8 clients have pinned a version, and only
  when its target is a final release on Maven Central; Gradle shows no relocation
  message. The maximum recovery time becomes a public option with 0.0.x's default of 360
  minutes; it does not drop below six hours.
- 2026-10-06, the relocation moved later: it is the old line's last act, published when
  0.0.x support ends, after the last 0.0.x fix, so no fix can undo it; until then the
  end-of-life notice and the README tell clients. Before its tag, someone confirms by
  hand that the remaining Java 8 clients have pinned a 0.0.x version.
- 2026-10-06, when Java 8 clients pin: not at the end-of-life notice, which would cost
  them the fixes until 31 March 2027, but on a reminder shortly before that date, to the
  last 0.0.x version.
- 2026-10-06, after a code review of `next`: follow-ups recorded with tickets 26 (the
  actor starts before the transport, the KD-12 and KD-27 scenarios check the second
  recovery), 31 (the AMQP client's buffer in the memory budget, fixture changes of a
  repeated replay run) and 41 (the initial snapshot interval on the producer's clock: dropped,
  entry of 2026-10-15).
- 2026-10-06, sessions after `open()`: `build()` throws instead of returning a session
  that never receives anything (section 3, difference 7; KD-29).
- 2026-10-07, ticket 26: the recovery actor runs in the feed, and every system test passes
  against 1.0; the list of scenarios 1.0 did not pass yet is empty and kept. The feed's
  events dispatcher outlives a failed start, so a retried start never delivers beside a
  callback of the failed one. A system test stamped a later alive earlier than the first;
  1.0 resumes from the newest subscribed alive by its timestamp, 0.0.x from the last one
  received, which differ only when a producer's clock goes back. A recovery-from timestamp
  set before `open()` starts the first recovery on 1.0; 0.0.x forgets it (KD-32). The alive
  ordering is KD-33.
- 2026-10-07, ticket 26, after the first review of the recovery's wiring: a snapshot complete of
  a request the feed did not ask for is dropped and counted as it is posted, and the same one is
  queued once per session until handled, so the actor's essential queue stays bounded by the
  feed's own requests; an event recovery whose caller stopped waiting is never asked for; a close
  that comes as the transport opens fails the open; the actor's close waits 200 ms at least past
  the shutdown deadline, so the resume points are final when `close()` returns. The connection's
  callbacks say nothing of the producers' state when they run, as in 0.0.x, which never took a
  producer down for a lost connection (KD-12): a producer's change is told on its own.
- 2026-10-07, ticket 26, `getHealth()`: typed, with a `counters()` map for metric export; a
  stale catalog is degraded after an hour, a lagging session at once. The counters it lacked
  are added: the transport's reconnects, a session's epoch discards and skipped acks, the
  entity caches' discarded stale fetches, and a public snapshot of the recovery's counters.
  The watchdog sets the `STALLED` states in the next step.
- 2026-10-07, ticket 26, the watchdog: one daemon timer thread from the feed's first start
  to its close. A session, the events thread, the broker's consumer threads and the
  recovery actor are `STALLED` after more than 30 s in one callback (a turn, for the actor),
  any of them and the alive dispatcher after 30 s with a queue that does not move; the
  JVM's threads while a deadlock holds some; the timers when the watchdog's own tick is
  more than 30 s late, which `getHealth()` finds on the caller's thread. Each is healthy
  again once that ends, logged and told once per change; the watchdog never interrupts a
  thread. The health now keeps the newest reading per part, so a reading of one part no
  longer holds back what an older reading of the whole feed found of the others. A
  connection that is down is not yet a degradation of its own: that waits for a decision.
- 2026-10-07, ticket 26, after the review of the watchdog: busy, still and overdue times are
  measured by `System.nanoTime`, not the wall clock; a watchdog stopped leaves the timers
  `HEALTHY` (a stopped watchdog is not a wedged one) and every other part as it last found it.
  An event recovery whose caller's wait ends in the instant the actor answers returns the id
  the request went out with, not null. The benchmark run takes no machine-wide JMH lock, so
  two builds at once no longer fail each other.
- 2026-10-07, ticket 26, close hardening after the final review: a session's thread that takes
  a message once `close()` has begun drops it unacknowledged, so no callback is admitted after
  that and no fact is posted for it, as in 0.0.x and the Go SDK; a callback admitted just
  before may still run, and `close()` waits for it within its shutdown timeout. The waits of the close for the sessions, the alive
  dispatcher and the recovery actor go on through an interrupt, the actor's 200 ms floor
  included, so an interrupted closer still reads final resume points. The sessions' queues drop
  what they hold when their channels close, and the alive queue's counters read empty once
  closed. The sessions closing at shutdown neither complete a recovery nor bring a producer up.
  One non-daemon thread keeps the JVM up from `open()` to `close()`, as 0.0.x's executors did.
- 2026-10-07, ticket 26, the watchdog's limits after the final review: the callback and queue
  limits follow the HTTP timeout, `max(30 s, 2 × timeout + 5 s)` (65 s for the default 30 s),
  so a callback whose read waits on a slow API - up to `2 × (timeout + 1 s) + 1 s` - is not
  reported `STALLED`, whose remedy, a new feed, would not help.
- 2026-10-07, ticket 26, clocks after the final review: the recovery actor paces its ticks and
  its park by `System.nanoTime`, so a wall clock set back no longer stops its ticks nor makes
  an idle actor read `STALLED`; the login-refusal minute is measured the same way. The
  machine's own durations stayed on the wall clock then, until the 2026-10-14 entry on clocks.
  The published resume point and a
  recovery's start are capped at the producer's now, so a timestamp from the producer's
  future moves neither past it; the recovery's marks follow in the 2026-10-16 entry on stamps
  ahead, the live state's watermark in the 2026-10-19 entry.
- 2026-10-08, documentation for the release candidate: KNOWN-DIFFERENCES.md is written against
  0.0.58, the stale entries are corrected, and KD-34 to KD-41 name the differences no entry had,
  the small ones grouped in KD-41. A producer clock that steps back keeps the code as merged; KD-28
  and KD-33 say what it can miss. The eager entity preload is planned with ticket 28, before
  1.0.0, and section 3 no longer tells clients to use it. Section 5 marks what the release
  candidate has and what is planned before 1.0.0, and the fields the schema does not declare are
  off it. Corrected here: a failed `open()` cannot be tried again, unlike 0.0.x; the start's
  90 s with the API down; how long a getter can wait; the thread table; completion per session
  and the processing delay.
- 2026-10-08, ticket 28's options for the fixed numbers: 0.0.x had no setter for the
  maximum inactivity or recovery time, only its constructor, which 1.0 keeps taking any
  value; the new setters refuse a maximum inactivity of 10 s or less and a maximum recovery
  time under six hours. The watchdog's stall limits stay derived from the HTTP timeout
  unless set; one set is refused below the SDK's own longest wait for the API and 2 s, when
  the configuration is built. A connection down for longer than 60 s, unless set, makes the
  new `CONNECTION` part `DEGRADED`.
- 2026-10-08, ticket 28's entity methods: `SportsInfoManager` has a sport, a tournament and a
  player by id, and the clears of a player and of a sport, as default methods. Like a match by
  id, each returns an entity that loads nothing until a getter is called, where the Go SDK
  fetches at once. There is no clear of a fixture or of a match status alone: the match's clear
  takes its fixture, and the live status is written by the feed. The system tests compile
  against both lines, so the methods 0.0.x does not have are tested over the fake REST server
  by unit tests.
- 2026-10-08, ticket 28's producer, replay and void-reason methods: `ProducerManager` has the
  active producers in a scope and a producer's status, `ReplayManager` the replay status, and
  `MarketDescriptionManager` the reload of the void reasons, as default methods. A producer's
  status is the one `onProducerStatusChange` last told, null before the first, as the Go SDK
  answers before a status is emitted; a change of the cause alone does not change it. The replay
  status is the API's string from `/replay/status`, null when the API fails, as the replay list
  answers. The system tests compile against both lines, so these are tested over the fake REST
  server by unit tests; the fake answers the replay status from the schema's fixture.
- 2026-10-08, ticket 28's locales and preload: 0.0.x had no setter for the default
  locale, and loaded every catalog and entity on its first read. The default locale gets a
  setter; the preload locales' catalogs (market descriptions, match statuses, sports, and
  the void reasons once) start loading in the background once `open()` has opened, as the
  Go SDK warms its catalogs at its start, but nothing waits for them and a failure is
  logged, not thrown, since `open()` has succeeded by then. The eager entity preload is off
  by default, so the API sees no more calls than with 0.0.x unless a client asks: as a
  message is queued for its session, its match is queued for loading on the side-loads,
  never loaded on the consumer or a session thread, deduplicated per match and locale,
  dropped and counted when the queue is full, logged and counted when it fails, and never
  waited for. A catalog preload that fails backs off no read: the first read after it
  fetches as it would have without the preload. The caches keep each locale apart, as 0.0.x
  did, so two locales of one language, such as `en_US` and `en`, are both preloaded,
  though the API, asked by the language, answers both alike: keying the caches by the
  language would change the locales 0.0.x's `getNames()` maps hold. A preload the feed's
  close cuts short is neither counted nor logged.
- 2026-10-14, a producer's recovery request has a permit of its own: the recovery pool's two
  permits were shared by whoami, the producer list, a producer's recovery request and every
  event recovery request, and up to 128 event recoveries can be in flight, so a producer's
  request waited behind each event request queued before it, which at a slow API is longer
  than the call's deadline. A fourth pool now carries whoami, the producer list
  and a producer's recovery request, with two permits, one for each of the API's two producers
  (prematch and live): with one, a slow recovery of one producer kept the other's from being
  sent at all, as the recovery actor sends the producers' requests at the same time. The
  recovery pool of two carries only the requests of single events, which are best effort,
  where a producer's is the safety net. Only the pool a
  request waits on changed: the recovery actor, its limits and the retry policy are as they were.
- 2026-10-14, the side-load workers follow the REST concurrency limit: four fixed workers
  paced a restart at four loads at a time whatever the limit, so at the default of 16 the
  eager preload used a quarter of the data permits, and with a limit under four the workers
  could take every one of them. The workers are now half the limit, at least one and at most
  16, eight by default: the preload's pace follows the option that already says how hard the
  SDK may hit the API, and half the permits stay free for the client's reads. No new option.
  The permits are fair, so a read was never starved, only queued behind the workers' requests.
  With a limit under four there is one worker, which a warm-up can hold, as the side-loads'
  rule for one worker says.
- 2026-10-14, clocks of the recovery machine (ticket K3 of the hardening list): the owner chose
  to measure every duration the machine takes between two local events on a monotonic clock,
  in three steps, which overtakes the sentence of 2026-10-07 that kept them on the wall clock.
  A wall clock that steps forward by more than the maximum inactivity, or a virtual machine that
  resumes, took every producer down by the wall reading of its last alive, and a step back
  stretched every deadline by as much; now neither happens. The first step gives each feed one
  clock and stamps each fact with both readings where it is received, the second moves the
  machine's durations to the monotonic one, the third moves how long an event recovery's status
  is kept, and adds a test that steps the wall clock under an open feed. What is published,
  and what is compared with a producer's own timestamps, stays wall time, as the Clocks
  paragraph of the recovery section says. KD-43 lists the difference from 0.0.x. No public
  API changes.
- 2026-10-15, the offset after a step: the residual that entry left - a message's age read
  against an offset measured before a wall step, so that a session's alive handled before the
  alive channel's copy took the producer down for a processing delay until the next sample - is
  closed. The offset is kept with the difference of the wall and monotonic readings of the alive
  that measured it, and moved, where it is read, by how far a fact's difference has moved since
  (above a second). Chosen over skipping the age check until the next alive: that would leave the
  cap and the recovery window, which also read the offset, wrong by the step, and a message
  really late after the step undetected for as long. No public API changes.
- 2026-10-15, clocks of the caches (follows the entry of 2026-10-14): that entry left the
  live-state and match-cache ages on the wall clock, since they compare two local readings. They
  are durations between two local events like the machine's, so a step of the wall clock changed
  them the same way: forward by more than the match status age, the feed's live status was taken
  over by REST and a summary loaded again; a step back kept a stale value fresh. They now run
  on the feed's clock, monotonic side, together with the entity caches' ages, the catalogs'
  refresh age, staleness and backoffs, the loaders' deadlines and the fixture changes' hour. A
  loader cannot be built with no clock; the other caches keep a constructor with none, which
  reads a system one, for a test, and the feed passes its own. A test over a clock whose two
  sides are moved apart pins each group, and a dependency test fails the build for a cache
  package that reads a clock of its own. No public API changes.
- 2026-10-15, the cold start's interval: the starts counted back from the initial snapshot
  interval stay on the SDK's clock; the shift into the producer's clock at its first alive, which
  the entry of 2026-10-06 recorded for ticket 41, will not be done. 0.0.x counts them on the
  SDK's clock too, the two differ only when the client's clock is off, and the request's start is
  already capped at the producer's now. No system test pins it, so there is no KD entry. No
  public API changes.
- 2026-10-15, a refused token is a health state: the owner chose to add `HealthComponent.API`
  (a public addition, on the differences list) instead of documenting that `getHealth()` does not
  show the refusal and that the client must listen for `onFatalError`. The part is `STALLED` from
  the first 401 or 403 until a call is answered with a success; of the three states `STALLED` is the one
  that says the feed cannot mend it and the remedy is a new feed, as the broker's permanent
  refusal is told by `onFatalError`. The REST client tells the health of every attempt, and only
  a refusal and a success change the part: a timeout, a 5xx or a 429 shows in the catalogs and
  the recovery counters, which the API outage scenario already watches.
- 2026-10-16, a dead recovery thread is reported at once: the owner decided that the `RECOVERY`
  part reads `STALLED` as soon as the thread has ended, with the reason "the recovery thread
  ended: <class and message>", not after the stall limit with "in one turn", which was false. No
  fatal error event, as before. The watchdog's sample carries what the actor records as its
  thread ends by a failure; a close records nothing. No public API changes.
- 2026-10-16, a thread that dies in a stall: a `RECOVERY` part already `STALLED` for a turn that ran
  too long, whose thread then ends, has the true reason logged once ("is still STALLED, for another
  reason"), and no second `HealthEvent`: an event is a change of state, the events dispatcher
  drops one whose state is the client's, and a client that alarms on `STALLED` would alarm twice.
  Only an ended thread does this; no other part's change of reason is told. No public API changes.
- 2026-10-16, a stamp more than a second ahead of its producer moves no recovery mark past the
  producer's now (the item the entries on the cap left open; the live state is left for a later
  change): a message stamped in the producer's future, from a component's fast clock or a bad
  value, kept a session's checkpoint and the producer's safe point ahead of what was processed,
  so a gap opened meanwhile started at the request and never asked for what lay between. The
  owner chose to clamp, with no tolerance setting: a mark is the stamp, or the producer's now at
  receipt when the stamp is more than a second ahead of it - the SDK's wall clock then, less the
  producer's most cautious offset of the last minute, corrected for a step of the wall clock
  since it was measured, as the recovery machine corrects it. The offset of the latest alive was
  wrong, as the alive dispatcher has kept an alive's own offset when the session's copy of it
  is handled, and a later alive has moved it when a message queued behind a callback is; each
  producer keeps the offsets of its alives over the minute, in an array replaced whole (a reader
  never waits for the alive dispatcher), with the newest one older than the minute, and "no
  offset" before the first, and the mark takes the largest. Clamping every message, which was
  tried first, lowered the mark of ordinary messages by the jitter of the alives' delivery and
  moved the recovery timestamps the system scenarios pin; the second absorbs it. A late alive
  still lowers the marks by its delay for a minute when the delay is over a second: a larger
  recovery, nothing lost. The live state was tried with the same mark and is not changed: a
  clamped stamp is a receipt time, which overwrites the order of the true stamps, and the largest
  offset of a late alive refuses correct writes. A status or score stamped in the producer's
  future still held back the updates that follow until the producer's clock passed it; the
  2026-10-19 entry changes that. No public API changes.
- 2026-10-16, the baseline moves to 0.0.59, a security release with no API change: the system tests
  and the API check run against it. It checks the broker's certificate and host name against the
  JVM's default trust (KD-19 now says up to 0.0.58 for the old behaviour), parses XML with DTDs and
  external entities off, and keeps the access token out of its logs, exception messages and a
  channel's description. No scenario expected 0.0.x to accept any certificate, so none changed: the
  fake broker's certificate is trusted by the JVM default in every test. No public API changes.
- 2026-10-19, the soak length is agreed: 72 hours on the test environment, a weekend day among them,
  the scenarios on the first day and two days of normal traffic with one fault a day after them.
  No public API changes.
- 2026-10-19, the age of a message by a stepped wall clock: the age that keeps a message older than
  the match status age from writing the live state (KD-14) was read by the producer's latest
  offset as it was measured, so a step of the SDK's wall clock since the alive made it wrong by the
  step until the next alive - a fresh message refused after a step forward, a message 25 minutes
  old written after a step back - the one place the offset was still read as it stood; the
  recovery machine's samples, marks, cap and window already read it as of the reading. It now
  reads it as of the dispatcher's own reading of the message, the way those do, which changes the
  signature of an internal method only: the sample of the safety net (processing delay) is not
  changed, it read the offset that way already. No public API changes.
- 2026-10-19, the Go and .NET sweep since 2026-10-04: Go `a9d29d9` to `d626983` (a version bump and lint
  tooling), .NET `66fcfca` (develop `9d9fe60`), nothing to port; the next sweep starts there. Two things
  came out of it. A market description with `outcome_type="player"` and an empty `<outcomes/>`, or no
  `<outcomes>` block, which Go's closed #65 asked about, was handled and is now pinned: the market is
  served, its description lists no outcomes and an outcome the feed sends is named as the player or
  competitor (`MessageFactoryTest`). And the .NET fallback of a match's tournament to the fixture when the
  summary fails is not taken, by the owner's decision: 1.0 keeps what 0.0.x and the Go SDK do (section 3,
  behaviour that stays). No code and no public API changes.
- 2026-10-19, the Go SDK's category, reference ids, statistics and settlement void reason are no
  longer a goal: the producer sends none of them and the schema declares none, so they are not
  planned, rather than waiting for a schema release. A bet cancel's void reason id and parameters,
  which the producer does send, were already read. No public API changes.
- 2026-10-19, stray snapshot completes: on the test environment a feed with no node id and the
  message interest ALL logged "a snapshot complete ... which this feed did not ask for" at WARN
  2,000 to 2,999 times in 30 minutes. The binding lets them in, as 0.0.x and the Go SDK bind it
  (the same keys, read from their code): a session of interest ALL binds every message with its
  node as `base.#` without a node id, so it takes the completions of every instance of the
  bookmaker, node-addressed or not; with a node id, `base.<node>.#` and `base.-.#`, so those of its
  node and of the instances without one; any other interest binds one completion key of its own,
  its node's or the no-node one. Checked on a real broker (`StraySnapshotCompleteScenarioIT`; the
  first guess, that a node id leaves a feed only its own node's completions, was wrong for ALL). So
  the line is expected, not a fault, and nothing is lost: the completion is dropped and counted as
  before. It is logged at DEBUG each, and the first of a feed once at INFO with the advice to give
  every instance a node id; 0.0.x and Go logged every one at INFO, which 1.0's levels (an hour of
  normal traffic is quiet) do not allow, and a WARN that repeats fails the soak check. The binding
  is unchanged. No public API changes.
- 2026-10-20, docs and examples text, from a check of the review leftovers against the code: the
  soak table no longer asks a TCP proxy for a login refusal, a 5xx or one producer's messages, says
  per duration what the refusal rule does (30 s is ridden out, three minutes end the reconnecting),
  counts the snapshot deadline from the request and from each snapshot message, names the tests
  that cover the slow client and the close during a recovery (and says none does it in a system
  test), exempts the slow client's deliberate stall from "any part STALLED", asks for an
  independent consumer to reconcile lost messages against, and limits the long run's counter rule to
  failure counters; the published-signature check fetches the key by the fingerprint in
  `RELEASE_KEY_FINGERPRINT`, which waits for the key; a callback's `match.getStatus()` is the cache's state, which a
  message the cache turned down did not write, and a fixture change is deduplicated for an hour
  within 100 000 changes; the active tournaments can come back short under `CATCH`. Section 5 no
  longer calls anything planned, and records that the Go SDK's name and abbreviation maps and a
  match's per-locale extra info stay out of 1.0, by decision of 2026-10-19; the 0.0.57 baselines of
  sections 3 and 10 are 0.0.59. The logging, replay, event recovery and resume examples handle an
  unparsable message, a refused replay call, a recovery still pending after five minutes and a
  start that fails. No SDK code and no public API changes.
- 2026-10-20, a read overtaken by a load that wrote loaded the key again: the cold start made 201 summary
  requests where it expects 200, about one run in sixty on CI. A read checks the entry's freshness and then
  asks the loader; the eager preload's load of the same match could write and leave between the two, and the
  loader, finding no flight to join, started a second fetch. Section 4's single-flight stays, and the reader
  now hands the loader its own freshness check, which the loader makes once more in the step that finds no
  flight (a fetch writes before it leaves the map): fresh, it returns and starts nothing; not fresh - the
  fetch wrote nothing, or an invalidation, a fixture change or a clear came after it, which leaves a
  tombstone - the reader's own fetch starts as before. No new flag or generation, so the write rule's
  generations and tombstones decide as they did. The same shape was in the match, fixture, live-state,
  competitor, player, tournament, sport and sport-list reads, and in the catalogs' cold read, background
  refresh and missing-item fetch (which compares the value held with the one it looked at), and is fixed in
  each. Not changed: a client's reload, which asks for a fetch whatever is held, and a catalog's preload and
  read, which never share a flight by design. Deterministic tests put a load of the key between a read's
  look and its load, and an invalidation after that load; the interleaving the loader's one step rules out
  is held by two threads. The 100 cold runs pinned to two cores made the expected requests every time, but
  the same 100 with the fix switched off did too, so they do not show the repair; the tests do. No public API
  changes.
- 2026-10-19, a status or score stamped in the producer's future no longer holds back the correct
  writes that follow (the live state half the 2026-10-16 entry left open; KD-13 narrowed, not
  removed: an older message still does not write). Three rules for the live state had failed
  review: a watermark clamped to the producer's now, which turns the order of true stamps into the
  order of receipts; a watermark distrusted by the producer's now at receipt, which a late alive
  moves, so that correct writes were refused under ordinary delivery delay; and the same with the
  largest offset, which reordered ordinary traffic. What replaces them uses no estimate of the
  producer's now: a message older than the watermark writes when it was received after the
  watermark's message, the time between their receipts plus the gap between their stamps is more
  than a margin, and its age by the producer's alives is at most a timely age; the watermark then
  goes down to its stamp (Ownership and ordering has the rule, with its limits). The margin and
  the timely age, 30 s and 10 s, are settings, `setFutureStampMargin` and `setTimelyMessageAge`:
  the owner chose settings over constants; each is positive, the margin at most a day, and the timely
  age at most the margin, checked together in `build()`, because a wrong takeover needs an alive
  later than their difference. A message that answers a recovery request never takes the
  exception, by the owner's decision: a recovery must not move the live state back, and the next
  live message repairs a bad stamp. A stamp is also refused the exception when it is not after
  the epoch: the age of a stamp near the least long wraps to a small negative one, so a hostile
  stamp would otherwise take the watermark to the bottom. The wall-step caveat of the age (a step
  of the wall clock reads as an age error until the next alive) is a separate change and is not
  made here. Public API additions only: the two getters and setters.
- 2026-10-20, the live state's quiet and fresh checks after a write received out of order: a message
  stamped after the watermark but received before the write that held it takes the lock later, and
  moves the receipt of the last write back. REST's check that the feed has been quiet and the
  freshness of a record's values read that receipt, so after such a write the feed was reported
  quiet, and the values aged, by the gap between the two receipts: REST could overwrite what the
  feed wrote moments before. Both now read the latest receipt of any write for the producer, which
  never moves back; the takeover rule keeps reading the watermark's own receipt. No public API
  changes.
- 2026-10-20, the stamp before a jump went stale: the live state kept the stamp a jump left behind
  (the floor a takeover must not go below) through every later write, so after a stamp ahead, a quiet
  match, a correct update and a second stamp ahead, the floor was still the first update's, and a
  timely message older than the correct update took the watermark over and put its older score
  back; a late message followed by a normal one left the same stale floor. A write that is in order
  and at the producer's now - its age by the producer's alives within the timely age on either
  side, which a stamp ahead is not - now forgets the floor, even when it is itself a jump, since no
  takeover can pass the watermark of a timely message; a replayed message and a recovery's answer
  carry no age and leave it. The end-to-end test of a stamp ahead followed by a correct message
  published them back to back, so a same-millisecond receipt or a stall made the correct message
  fail the takeover's rule; it now waits for the first to reach the listener, lets the clock move
  on, and stamps the second as it is published. No public API changes.
- 2026-10-20, the takeover and the jump counted time from the watermark's own receipt: after a write
  received out of order - a newer stamp received earlier, handled later by another session - that
  receipt is older than the latest one, so a message a millisecond behind the latest write looked as
  slow as the gap to the watermark's receipt and took over (a swap of two messages rolled back the
  newer), and a jump was missed by the same gap, so no stamp was left behind for an older message to
  be refused by. Both now count from the latest receipt of a write for the producer, which only grows
  and which a takeover already had to be after. No public API changes.
- 2026-10-20, the stamp before a stamp ahead was recorded only by a jump: a stamp ahead of the
  producer by less than the margin plus the delay between two receipts - 25 s ahead, 100 ms
  after the update - raised the watermark by less than the jump's threshold, left nothing behind,
  and a timely message older than the update took over and rolled it back. A write whose corrected
  age is below the timely age negated now records it, jump or not, and keeps it through the
  stamps ahead that follow; a write at the producer's now still forgets it, a late one leaves it.
  The first stamp of an entity being itself ahead records none, so two bad stamps in a row at the
  start do not make the first the floor. The jump is asked only of a write with no corrected age,
  a replayed message or a recovery's answer: with one, every jump is a stamp ahead, a message at
  the producer's now, or a late message that no timely one can be older than. The timely message
  age is now at most half the margin (the builder refuses more, and defaults to half the margin
  under 20 s): a watermark at the producer's now is no more than the timely age from a timely
  message, so R + G of the two cannot pass the margin; before, a timely age over half could
  still let a takeover pass. The two settings are new in 1.0 and not released: no public API
  changes.
- 2026-10-20, the old 0.0.x source left `next`: `library/`, the Kotlin copy of the 0.0.x SDK, was last changed
  before 0.0.56 and differed from `release/0.x` in 42 files, so a review of the whole tree kept finding defects
  there that are fixed on `release/0.x`, where the 0.0.x line lives and is maintained. It is removed, with the
  Gradle build of the old examples (`build.gradle`, `settings.gradle`, the wrapper) and the examples' logging
  file; that is the library part of ticket 44. The examples' Java sources stay in `examples/src`: a test of
  `odds-feed` (`ApiUsageTest`) compiles them against the 1.0 jar, as the checks section says, and `release/0.x` has
  the same sources with their build. The README, the examples' README and `odds-feed/CACHE-FIELDS.md` point
  to `release/0.x` for the 0.0.x code; the references in the review log and in the tickets are left as they
  were written. No public API changes.
- 2026-10-20, two checks of the CI scripts missed a case. The ownership test derived the files the
  release runs only from literal `$root/...` and `$here/...` paths, so a helper a release script
  calls through its own directory (`dirname "$0"`) was owned only by the `release-*` pattern: one
  outside it would have run with the signing key at hand, owned by nobody, and the test passed. It
  now derives those paths too and follows the scripts among them to the end, a cycle left; the
  files it finds today were all owned already. It resolves each path against the directory the script
  sets `here` to, reads a doubled slash as the shell does, and follows no symlink out of the tree; the
  whole of `scripts/` is also owned, as a backstop for what a lexical scan cannot see. The schema drift check took a rename for XML when
  either name held `.xsd` anywhere, so `a.xsd -> b.txt` compared the text file as a schema, and
  `a.txt -> b.xsd` compared the old file with the new; it now compares only the side that ends in
  `.xsd` and takes the other as absent (the file left the schema, or entered it), and skips a
  rename between two files that are none. No public API changes.
