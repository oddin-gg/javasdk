# Java SDK 1.0 – design plan

Status: draft, waiting for review.

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
- **Missing features compared with the Go SDK.** Category, reference ids, statistics,
  single-item getters, cache clear methods, default locale, timeouts, prefetch.

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
  only. Clients who want zero latency in callbacks use the preload options in section 5.
  The same holds on the events dispatcher: a getter inside a producer-status callback
  delays other events, which the documentation of that listener says.
- `ExceptionHandlingStrategy` keeps its meaning, getter by getter as 0.0.57 has it.
  Default stays `THROW`.
  - Under `THROW` a failure reaches the caller as the exception 0.0.x threw there. An
    entity or catalog getter that cannot load what it reads throws
    `ItemNotFoundException`, with the API's `ApiException` as its cause. A manager
    method that asks the API directly throws the `ApiException` itself: the schedules,
    the live matches, the fixture changes, a sport's available tournaments, the void
    reasons.
  - Under `CATCH` the getter logs and returns null. A collection getter never returns a
    partial list: under `THROW` the first failed part fails it, under `CATCH` the whole
    collection is null.
  - 0.0.x's exceptions to both rules stay. Under either strategy the sports, the active
    tournaments and the market descriptions are an empty list when they cannot load,
    and a market description by id is null. A match's competitors are none under
    `CATCH`. A sport whose tournaments cannot load is left out of the active tournaments
    under `CATCH`. An outcome's name and a match status description, which 0.0.x read
    from what it held, are null when they cannot load, under `THROW` too.
  - A member list is its entity's id list: a match's or a tournament's competitors, a
    competitor's players. It returns at once, as 0.0.x's lazy members did: the members'
    profiles start loading in the background, on the side-loads a message's preload
    leaves idle, so a long list never crowds the preload out, and a reader of a member
    joins its load. A member whose profile cannot load is still listed, and its own
    getters fail.
  - Every collection a getter returns is a new mutable one, the caller's own.
- The cache is updated before the listener callback runs, so `match.getStatus()` inside
  `onOddsChange` sees the state the message carried. Same as today.
- Multi-session with the priority-split interests and the interest-combination
  validation stays exactly as today. A client may still create its own
  `SYSTEM_ALIVE_ONLY` session next to live sessions. A replay session stays the only
  session of its `OddsFeed`: 0.0.x gives it the interest `ALL`, which the validation
  allows only for a single session, so replay has always run on an instance of its own.
- Fixture-change deduplication across sessions stays with today's key: producer, event
  id and change timestamp, remembered for one hour.
- Recovery messages are delivered to the client like any other message, on every
  session whose interest matches, as today.
- Recovery methods keep returning the request id as a `Long`. A new status lookup by
  request id is added next to them, not instead of them. As in 0.0.x the caller's thread
  waits for the API's answer, once the feed is open: for the HTTP timeout and a second at
  most, then null. A replay feed runs no recovery and accepts none, nor does a closed one.
- The feed starts lazily, as today: the first call of a manager getter or of
  `getSessionBuilder()` fetches whoami and the producer list (section 4, REST) and builds
  the caches, catalogs and managers. One start runs at a time, and callers that come
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
  new one. Same as today. Once `open()` has taken the sessions, a second call throws
  `InitException` ("feed cannot already opened", 0.0.x's words), whatever came of the
  first; only an `open()` without sessions ("Feed created without sessions") leaves the
  feed as it was. It checks the interests before it disables any producer or connects.
- `close()` can be called at any time, more than once, and from a callback: it stops
  delivery, tells every thread to stop, then waits for all of them within one shutdown
  timeout of five seconds, not one per thread; it does not wait for the callback it is
  called from, nor for an `open()` under way, which then fails and closes what it
  started. A callback still running at the deadline is left to end on its own daemon
  thread, and the log says so. The order: the recovery actor is told the feed is closing,
  then every dispatcher is told to stop and each session's close is posted to the actor;
  the dispatchers are waited for, then the actor, which handles what is queued within what
  is left of the deadline, then the connection closes, so the actor hears of no loss the
  close makes.
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
| AMQP consumer | one executor per connection, sized to sessions + 1 | hand-off of raw deliveries into session queues; the alive hand-off | decode, build, cache writes, client code, anything blocking |
| Session dispatcher | one thread per session | decode, build, cache write, client callback, ack, age sampling | nothing else |
| Alive dispatcher | one thread | alive decode, clock offsets, posting liveness facts to the recovery actor | REST, client code |
| Recovery actor | one thread | **all** producer and recovery state: liveness, checkpoints, completions, caps, resets, the safety-net decision; it looks at the clock itself at least once a second | REST calls and channel resets (it posts them to REST workers and receives the result as a message), client code |
| Events dispatcher | one thread | every non-message client callback: connection state, producer status, health, fatal errors, listener exceptions, API call events, recovery completion | message callbacks |
| REST workers | virtual threads | HTTP calls, posting results back to whoever asked | client code |
| Timers | one scheduled executor | scheduling only, each tick posts a message to an actor or a worker | blocking work, client code |

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
on the session's own thread, bounded by the session's queue. A wedged events dispatcher
is reported by the watchdog through `getHealth()` and the log, because the health event
itself would queue behind the wedge.

### Delivery

- One AMQP connection per `OddsFeed`. One channel and one exclusive queue per session,
  as today. `basicQos(prefetch)` per channel. Prefetch is configurable in the range
  1 to 10 000, default 200. Zero is rejected, because the broker reads it as unlimited.
- The SDK always runs its own alive consumer on its own channel, whatever sessions the
  client created. Its consumer callback hands the raw alive to the alive dispatcher and
  acks. That hand-off holds at most a thousand alives and a megabyte of them; one with no
  room is dropped and counted. The producers' pace keeps it near empty, and a dropped
  alive costs the recovery actor a beat, not a wrong state: an unsubscribed producer says
  so in every alive, and silence takes a producer down. A client's `SYSTEM_ALIVE_ONLY` session, if any, is an ordinary session and does
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

- Three permit pools per `OddsFeed`. The recovery pool covers recovery requests and
  producer and whoami calls, size 2. The catalog pool covers market descriptions, void
  reasons, status descriptions and sports, size 2. The data pool covers entity loading,
  default 16, configurable. Nothing else can delay a recovery request behind it.
- Every REST call runs under one deadline, the configured HTTP timeout, which covers
  permit wait, the call, and every retry. Retries happen only inside the deadline. A
  getter that fetches therefore waits at most the HTTP timeout, and that is the number
  the configuration option documents.
- A permit is held for one HTTP call only, never across a fan-out. A match load
  releases its permit before its competitor loads acquire theirs, so nested loads
  cannot hold every permit in parents while children wait.
- Independent calls run in parallel on virtual threads, under the data pool.
- Retries only for idempotent calls, with backoff, inside the deadline. HTTP 429 and
  `Retry-After` are honoured within the same deadline. 401 and 403 are permanent: the
  call fails at once, nothing retries, and a fatal error event goes to the events
  dispatcher in addition to the caller's exception or null.
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
- Side-loading queues never block the producer. When full, they drop and count. Each
  side-load has one deadline too, from when a worker starts it.

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
  timestamp does not write feed-owned fields. It is still built and delivered; the
  watermark orders cache writes, never delivery. Messages that carry no feed-owned
  fields (settlements, cancels, bet stops) are not watermark-checked at all. Snapshot
  messages, recognisable by their recovery request id, write like live messages but
  never advance checkpoints (see recovery).
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
    asking the recovery actor; before the producer's first alive it is taken as none.
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
  refresh. A market or match status missing from a list fetches the list again, once
  per missing id and only when the list is at least a minute old, so one added
  upstream shows up before the next refresh. The read that missed waits for that
  fetch, within its deadline, and finds what is new: like a getter on a cold entity
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
  (existing setter) seeds every session's checkpoint at `open()`. To resume after a
  restart, a client persists the producer's resume point, the oldest of its open gaps'
  starts and its sessions' checkpoints, and passes it back through
  `setProducerRecoveryFromTimestamp`. `Producer.getTimestampForRecovery()` reports it
  once the feed is open, as the actor publishes it after each fact, and the client's own
  value before; 0.0.x reported the last alive, which can be ahead of both (KD-28).
  `getLastProcessedMessageGenTimestamp()` keeps its meaning. A session closed while
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
  resuming. A recovery's start is clamped
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
- Snapshot completion is tracked per message interest, as today: a producer is up
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
  Go SDK's deadline (recoveries completed there in 83 to 139 s); 0.0.x and the .NET SDK
  have none and wait out the maximum recovery time (KD-25). That time stays the bound on
  a recovery that keeps coming, and on event recoveries. Ticket 28 makes it a public
  option; its default stays 0.0.x's 360 minutes.
- Recovery that the API does not accept, or that times out (either deadline), is
  re-issued with backoff from five seconds, doubling, at most three times in a row.
  After that the producer stays down and the client gets a producer-status event with
  the reason. The cap re-arms after a cool-down of ten minutes, and immediately when an
  alive arrives after a gap. Nothing stays down for the process lifetime without a
  further attempt.
- Event recoveries take their ids from the same sequence. At most 128 are in flight per
  producer, and one without a `snapshot_complete` within the maximum recovery time is
  dropped and counted, as is one whose `snapshot_complete` went with a lost queue; a
  caller still waiting for the API's answer then hears it was not accepted. The first
  `snapshot_complete` of a request, from any session, says the API took it: an event
  recovery's caller hears the request id then, while the completion still waits for the
  other sessions, and for a producer or an event recovery alike a late answer, even a
  failure, changes nothing - no second request, no failure counted. One asked
  for while the connection is down is refused at once, since its snapshot would have no
  queue to go to. The caller's future is completed on a thread of its own, never the
  actor's, so nothing the caller chains to it can hold the actor up.
- Each event recovery has a status by its request id, as in the Go SDK: pending from
  the request, then completed, failed or timed out. Failed means the API refused it, its
  `snapshot_complete` went with a lost queue, or the feed closed first. Unlike the Go
  SDK, a producer going down for another reason fails none, since its snapshot can still
  arrive. A status is kept five minutes after it ends, then forgotten; an id never asked
  for, or forgotten, has none, and producer recoveries have none. Pending ones are bounded
  by the event recoveries in flight; of the ended ones at most 10,000 are kept, and past
  that the first to end is forgotten early, and counted. The actor writes the statuses
  and any thread reads them; `RecoveryManager.getEventRecoveryStatus(requestId)` returns
  them as the public `EventRecoveryStatus`, whose `State` is `PENDING`, `COMPLETED`,
  `FAILED` or `TIMED_OUT`.
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
  `snapshot_complete`s, one per request per session, by the recoveries asked for on the
  node id - which include another instance's sharing it, the one input there not
  bounded by this feed alone.
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
    decided defaults, fixed until ticket 28's options make them settable.
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
  host name checked (0.0.x trusted any certificate), the virtual host whoami names (0.0.x
  built `/oddinfeed/<bookmaker id>`, the same today), a 10 second heartbeat, and the host
  as configured, tried once - the client's own resolver tries every address of a host,
  and a refused login on the first then reads as a network failure on the last.
- Reconnect with backoff on network failures. Exclusive queues are always re-declared;
  whatever the broker buffered for the old queue is gone, and recovery covers it.
- A channel the broker closes or cancels while the connection stays up is opened again
  by the transport, with a pause from a second, doubling to thirty while it fails; 0.0.x
  left it closed (KD-27). The session is told of the loss before the new channel can
  deliver, and of the new channel once its queue is bound, and recovery covers what the
  old queue held.
- Authentication and authorisation failures and a wrong virtual host are treated as
  permanent once at least three refusals have gone on for a whole minute with no
  connection in between. They are counted by the clock, not by attempts, which the
  backoff spaces a few seconds apart at first, because a refusal can be an auth backend
  being deployed or a virtual host being written. Permanent means the reconnect loop
  stops and a fatal error event carries the broker's reason, with how many refusals
  over how long. The client's exit is `close()` and a
  new `OddsFeed`; `open()` is one-shot, as today.
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
  attribute or prefix name longer than 128 characters; an element at most 64
  attributes and namespace declarations together; an attribute value, a declared namespace
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
`ThreadMXBean` for monitor deadlocks. Every internal blocking wait in the SDK has a
deadline, so a wedge on a permit, a latch or a queue, which `ThreadMXBean` cannot see,
turns into a timeout with a counter instead of a silent hang. A dispatcher inside one
callback for longer than the configured limit, an executor whose queue has not moved
while non-empty, or a reported deadlock produces a loud log line, a health event on the
global listener (`onHealthEvent`: the component, its previous and new `HealthState`, a
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

Remediation is limited and stated: the watchdog does not kill threads. A wedged
dispatcher is reported; the client's remedy is `close()` and a new `OddsFeed`, and
`close()` uses the shutdown timeout so a wedged callback cannot block shutdown. A stall
must never be silent again, but the SDK cannot unwedge client code.

---

## 5. New in 1.0

Additive only, and every addition is on the differences list (section 3). All of it
exists in the Go SDK already.

- Entities: `Category` on tournaments, reference ids on matches and tournaments,
  `Statistics` on match status, `IconPath` and `Abbreviation`, competitor ids on
  tournaments, tournament ids on sports.
- Getters: single `Sport`, `Tournament`, `Player` by id, `ProducersInScope`,
  `ProducerStatus`, replay status, recovery status by request id, `getHealth()`.
- Cache control: clear methods per entity type, reload of void reasons.
- Configuration: default locale, preload locales, eager entity preload for messages,
  HTTP timeout, startup deadline, prefetch, maximum message size, REST concurrency
  limit, max inactivity, max recovery time, stale-message limit and window, exchange
  names, shutdown timeout, API call logging, the broker connection's own TLS context
  (`setMessagingSslContext`, for a truststore of the client's own or a proxy that
  inspects TLS).
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
`setSpecificEventsOnly`, raw API data callback.

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
   0.0.57 first, so we know each test actually tests something. Then they run against
   1.0.0. Same tests, one version property. CI runs the suite twice, once per version,
   and the 1.0.0 run asserts the loaded jar's version through the telemetry getter, so
   a misconfigured build can never pass by silently testing the downloaded old jar.
   Resolving 0.0.57 needs a GitHub Packages token; CI has one.
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

---

## 8. Performance

Performance is a requirement, not a follow-up.

- A benchmark harness in the repo: generated odds changes shaped like a live match's,
  run through decode, cache and entity build, with JMH. Budgets per message for time and
  allocation. Runs in CI as a regression check. It has a **cold scenario** as well: a
  restart-shaped run where every entity is a miss and eager preload is on, against the
  fake REST server with realistic latency, with a budget on time-to-caught-up. The
  cold path is the one that decides whether a client trips the safety net after a
  restart.
- Rules for the hot path: no copying of cached descriptions to read one field, one
  lookup per market and locale per message, names resolved lazily on first
  `getName()`, no `String.format` or boxing in loops.
- Cold path: parallel bounded fan-out for competitors and players, single-flight,
  under the data permit pool.
- Outage: serve stale data while a refresh is failing, back off per locale. Never
  collapse to one message per HTTP timeout.
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
14. HTTP client: all endpoints, three permit pools, one deadline per call covering
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
    tests.

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
        of tickets 21 and 24. Also the starts counted back from the initial snapshot
        interval, which are on the SDK's clock: they are shifted once into the
        producer's clock at its first alive. 0.0.x counts them on the SDK's clock too;
        the two differ only when the client's clock is off, and that gets a KD entry if
        a system test pins it.
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
    well as 0.0.57, in CI; `getHealth()` and the watchdog remain.

    42. Concurrency stress suite on the assembled SDK, in CI.

### Phase 3 – Parity and polish

27. Field parity with the Go SDK, in small groups.
28. Option and method parity. Includes the safety-net, inactivity, recovery-time and
    shutdown-timeout options, the locale preload, and the 1.0-only test that pins
    KD-17. The maximum recovery time keeps 0.0.x's default of 360 minutes.
29. Telemetry, done: the REST headers and the public version getter with ticket 14,
    `SDK_version` in the broker connection's client properties with ticket 21.
30. Logging cleanup. Noisy logs are a client complaint.
31. README, examples, release notes and upgrade guide, integration guide with the
    onboarding checklist (distinct node ids, the operator's queue limit above prefetch,
    the per-session memory budget with the AMQP client's own buffer) and resuming after
    a restart, FAQ update. The replay guide says that a replay run again within the
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
  The system tests against 0.0.57 are our best defence. Release candidates to clients
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
that keeps coming, and event recoveries.
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
  carries a row for it. A miss refetches the list once instead of on every miss as
  0.0.x did. A read with nothing to serve fails at once while its key backs off, which
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
  repeated replay run) and 41 (the initial snapshot interval on the producer's clock).
- 2026-10-06, sessions after `open()`: `build()` throws instead of returning a session
  that never receives anything (section 3, difference 7; KD-29).
- 2026-10-07, ticket 26: the recovery actor runs in the feed, and every system test passes
  against 1.0; the list of scenarios 1.0 did not pass yet is empty and kept. The feed's
  events dispatcher outlives a failed start, so a retried start never delivers beside a
  callback of the failed one. A system test stamped a later alive earlier than the first;
  1.0 resumes from the newest subscribed alive by its timestamp, 0.0.x from the last one
  received, which differ only when a producer's clock goes back. A recovery-from timestamp
  set before `open()` starts the first recovery on 1.0; 0.0.x forgets it (KD-32).
