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
   using them from Java.
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
7. A replay session created next to live sessions on one `OddsFeed` no longer writes
   replayed state into the live caches, producer liveness or recovery checkpoints. It
   did, and that was a bug: replayed old scores overwrote live ones.

### Behaviour that stays, and that the implementation must not "fix"

- Entity getters such as `match.getName(locale)` or `match.getCompetitors()` are
  synchronous and may fetch from REST when the cache is cold. They run on the caller's
  thread. A callback that touches a cold entity pays that latency on its own session
  only. Clients who want zero latency in callbacks use the preload options in section 5.
  The same holds on the events dispatcher: a getter inside a producer-status callback
  delays other events, which the documentation of that listener says.
- `ExceptionHandlingStrategy` keeps its meaning. `THROW` propagates failures from
  getters to the caller, `CATCH` logs and returns null. Default stays `THROW`. A
  collection getter never returns a partial list: under `THROW` the first failed part
  fails the getter, under `CATCH` the whole collection is null.
- The cache is updated before the listener callback runs, so `match.getStatus()` inside
  `onOddsChange` sees the state the message carried. Same as today.
- Multi-session with the priority-split interests and the interest-combination
  validation stays exactly as today. A client may still create its own
  `SYSTEM_ALIVE_ONLY` session or a replay session next to live sessions.
- Fixture-change deduplication across sessions stays with today's key: producer, event
  id and change timestamp, remembered for one hour.
- Recovery messages are delivered to the client like any other message, on every
  session whose interest matches, as today.
- Recovery methods keep returning the request id as a `Long`. A new status lookup by
  request id is added next to them, not instead of them.
- `open()` is one-shot. After a fatal error the client closes the feed and creates a
  new one. Same as today.
- Delivery is at most once, as today. Exclusive queues die with the connection, so an
  unacknowledged message is never redelivered. The gap is closed by recovery, not by
  redelivery. Acking late buys backpressure, not at-least-once.

### How we check it

- The old `examples` module compiles against the new jar.
- A curated `api-usage` module calls every public signature once. It compiles against
  0.0.56 and against 1.0.0 in CI. This is the real source-compatibility gate.
- A reflection test walks the public entry points, collects every reachable type, and
  fails if one lives in an `internal` package, or has no counterpart of the same name
  in the 0.0.56 jar and is not on the **additions list**, a file in the repo that
  names every type and method added in 1.x, reviewed like code. A reachable type
  missing from `api-usage` fails the test too, so the curated module cannot drift.
- A jar diff (japicmp) against 0.0.56 runs as an advisory report with an allowlist for
  the accepted differences. It answers binary questions, not source ones, so it does
  not gate.
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
| Recovery actor | one thread | **all** producer and recovery state: liveness, checkpoints, completions, caps, resets, the safety-net decision | REST calls (it posts them to REST workers and receives the result as a message), client code |
| Events dispatcher | one thread | every non-message client callback: connection state, producer status, health, fatal errors, listener exceptions, API call events, recovery completion | message callbacks |
| REST workers | virtual threads | HTTP calls, posting results back to whoever asked | client code |
| Timers | one scheduled executor | scheduling only, each tick posts a message to an actor or a worker | blocking work, client code |

Decision 10 in one sentence: client code runs on a session dispatcher or on the events
dispatcher, nowhere else. The recovery actor in one sentence: nobody touches producer
or recovery state except by posting to it, so the state machine has one owner and no
locks.

The events dispatcher has two queues. The control queue carries connection state,
producer status, fatal errors, health and recovery completion; it is bounded at 10 000
and a full queue is counted and logged, never blocked on. The telemetry queue carries
API call events and listener-exception reports; it is bounded at 1 000 and drops the
oldest with a counter. A wedged events dispatcher is reported by the watchdog through
`getHealth()` and the log, because the health event itself would queue behind the
wedge.

### Delivery

- One AMQP connection per `OddsFeed`. One channel and one exclusive queue per session,
  as today. `basicQos(prefetch)` per channel. Prefetch is configurable in the range
  1 to 10 000, default 200. Zero is rejected, because the broker reads it as unlimited.
- The SDK always runs its own alive consumer on its own channel, whatever sessions the
  client created. Its consumer callback hands the raw alive to the alive dispatcher and
  acks. A client's `SYSTEM_ALIVE_ONLY` session, if any, is an ordinary session and does
  not carry producer liveness.
- The consumer callback for a session channel does one thing: it puts the raw delivery
  (body bytes, envelope, delivery tag, channel epoch) into that session's queue. The
  queue holds at most `prefetch` entries per epoch and is drained of old epochs before
  a new channel's consumer is registered, so the broker's limit of `prefetch`
  unacknowledged messages per channel is the queue's bound. The hand-off never blocks
  and the consumer executor is never busy for longer than a queue put, so one session
  cannot delay another session's deliveries or the alive channel.
- A body larger than the configured maximum message size (default 1 MiB) is not
  decoded. It is counted, reported as unparsable, and acked. The per-session memory
  budget is therefore `prefetch` times the maximum message size, and the configuration
  documentation says so.
- The session dispatcher takes a raw delivery, decodes it, builds the message object,
  writes the feed data into the caches, runs the client callback, then acks. Order is
  preserved per session. A slow callback lets unacked messages pile up to `prefetch`
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
- Partial failure in a parallel fan-out: `THROW` fails the getter with the first error;
  `CATCH` returns null for the whole collection. Never a short list.
- Startup: `open()` needs whoami and the producer list. It retries them inside a
  startup deadline (default three times the HTTP timeout), then fails with a clear
  exception. It never blocks indefinitely and never starts half-configured.

### Caches and loaders

Structure:

- A cache is a bounded map with values, per-key metadata and no I/O. It never calls
  anything that can block. A **loader** owns the fetching: single-flight, permits,
  deadlines, merging a response into one or more caches. Loaders call caches; caches
  never call loaders. An architectural dependency test (ArchUnit or equivalent) fails
  the build if the cache package depends on the loader, HTTP or AMQP packages. This
  is the structural form of decision 11; the latch-based deadlock tests remain as a
  second net.
- Single-flight is in the loader. Concurrent misses for the same key wait for the one
  fetch under the same deadline as the fetch itself plus a margin. Waiters that time
  out fail with the exception strategy; they do not start their own fetch.
- Side-loading queues never block the producer. When full, they drop and count.

Bounds and freshness:

- Caffeine. Entity caches have a maximum size and expire after write with the same
  ages as today: match, fixture and tournament 12 hours, competitor and player
  24 hours, match status 20 minutes. Freshness is tracked per locale block inside an
  entry: each locale's data carries its own fetch time, and a locale older than the
  entry's age is refetched on read even if another locale was written recently.
  Nothing is unbounded. No soft references.
- Catalog caches refresh after write. An expired entry is served while the refresh runs
  and while it fails, up to a maximum staleness of 24 hours. Past that the entry is
  treated as missing and the caller gets the exception strategy. Serving stale raises a
  health state. Failed refreshes back off per locale.
- The caches hold entities, statuses and catalogs. They do not hold market state or
  odds; those live only in the messages the client receives.

Write rule:

- Every field of every cached entity has one **authoritative endpoint**. For a
  competitor the authoritative endpoint of its player list is the competitor profile;
  of its name per locale, the profile in that locale. For a match status it is the
  match summary. Ticket 16 carries the full table.
- An authoritative response replaces the fields it is authoritative for, in the locale
  it was fetched for, and marks them **authoritatively written**. A field the response
  omits is cleared and stays marked. That is how a retracted winner disappears from
  the summary. Locale-independent fields are written by every authoritative response
  regardless of locale; two parallel locale fetches carry the same server state, so
  last writer wins is correct, and omission-clear applies to them only when the
  endpoint always serialises the field when it exists.
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
  by then holds a fresh value or starts a fresh fetch. Fill-only writes do not check
  generations; they can only add what is absent and unmarked.

Ownership and ordering:

- Feed messages own live status, scores, period scores and the match clock. REST owns
  everything else. Market state and odds are not cached.
- The feed watermark is the `timestamp` of the last live feed message that wrote
  feed-owned fields, kept per entity and producer in a bounded record with an age of
  24 hours, longer than the status entry it protects, so an evicted status does not
  forget how recent the feed was. A message from the same producer with an older
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
- Within a feed message, a missing optional scalar means "keep what you have", never
  "reset to zero". Both schemas mark scores optional.
- Fixture-change deduplication is one shared, concurrent map per `OddsFeed`, keyed by
  producer, event id and change timestamp, bounded in size and expiring after one
  hour. Evictions before expiry are counted. All session dispatchers consult it before
  delivering.
- Caches, producer state and checkpoints belong to one `OddsFeed` instance. Replay
  messages on an instance that also has live sessions do not write feed-owned fields,
  do not advance checkpoints and do not feed producer liveness (difference 7). On a
  replay-only instance they do all three.

Locales and catalogs:

- Loaded-locale marks are stored with the values they describe and share their
  lifetime. A mark is set only by an authoritative fetch for that locale. New sports,
  tournaments and markets show up without a restart.
- Catalog entries carry their provenance: bulk-listed or individually fetched. A
  refresh of a list endpoint replaces the bulk-listed entries for that locale and
  leaves individually fetched ones (dynamic market variants) alone; those expire on
  their own age. A market or tournament removed upstream disappears on the next
  refresh.
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
  producer is the running maximum of the `timestamp` of live (non-snapshot) messages
  it has finished for that producer. When the alive dispatcher observes an alive for a
  producer and a session's queue holds nothing for that producer, the session's
  checkpoint advances to the alive timestamp, because everything sent before that
  alive has been processed. Snapshot messages never advance a checkpoint, so a retry
  after a partial snapshot starts from the same point as the first attempt.
- A recovery for a producer starts from the oldest checkpoint among the sessions that
  receive it. A client-supplied recovery-from timestamp (existing setter) seeds all of
  them before `open()`. The point is clamped to the producer's stateful recovery
  window, as today, and a cold start with no seed requests a full snapshot.
- Session lifecycle: a session that closes leaves the checkpoint and completion sets
  at once. A session that opens is seeded with the producer's current recovery-from
  point and triggers a recovery for its interests, as today on `open()`.
- Snapshot completion is tracked per message interest, as today: a producer is up
  again when every session that receives it has seen its `snapshot_complete`.
- Requests for one producer are coalesced: while a recovery is in flight, further
  triggers join it instead of issuing a second one, and their reasons are recorded.
- Recovery that times out is re-issued with backoff, at most three times in a row.
  After that the producer stays down and the client gets a producer-status event with
  the reason. The cap re-arms after a cool-down of ten minutes, and immediately when an
  alive arrives after a gap. Nothing stays down for the process lifetime without a
  further attempt.
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
    is paused for a producer, on every session, while any recovery for that producer
    is in flight. This stops one session's snapshot from tripping another session.
  - When the age of live messages from a producer stays above the configured limit for
    the configured window, the actor first requests a recovery for that producer from
    the oldest checkpoint. Only when the request has been accepted does it ask the
    AMQP layer to replace the session's channel. A rejected or failed request means no
    reset: the actor backs off, counts, and raises an event. Data is never dropped
    before its replacement is on the way.
  - Each reset raises an event and increments counters (resets, messages dropped by
    the reset, epoch discards), so an operator can see exactly when and why.
  - The net backs off between resets and has its own cap of three per session per
    cool-down. When spent, the net stops resetting: messages keep flowing under
    backpressure, the session is marked "lagging" in `getHealth()` with a health event,
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
- Reconnect with backoff on network failures. Exclusive queues are always re-declared;
  whatever the broker buffered for the old queue is gone, and recovery covers it.
- Authentication and authorisation failures and a wrong virtual host are treated as
  permanent after three consecutive occurrences within one minute, because a single
  refusal can be an auth backend blip. Permanent means the reconnect loop stops and a
  fatal error event carries the broker's reason. The client's exit is `close()` and a
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
- The decoder is the untrusted-input boundary. DTDs off, external entities off. Body
  size is bounded before decoding by the maximum message size from the delivery
  section; the decoder's own limits bound parser work. One malformed document costs
  one unparsable callback, nothing more.
- Unknown enum values decode to an `UNKNOWN` constant, and the message keeps the raw
  string in a separate getter next to the enum getter. Unknown attributes and elements
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
global listener (new `default` method), and a state change in `getHealth()`.

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

Additive only, and every addition is on the additions list (section 3). All of it
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
  names, shutdown timeout, API call logging.
- Events on the global listener, all as `default` methods: connection state changes,
  health events, listener and pipeline exceptions, fatal errors, safety-net resets,
  API call events with method, URL, status and latency, producer-status reasons that
  name the cause.
- Raw data: `default` methods on the extended listener delivering raw XML bytes for
  feed messages and REST responses, and raw-string getters next to enum getters.
- Telemetry: SDK version in the HTTP `User-Agent` and in AMQP client properties.

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
a test like the new one.

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
   0.0.56 first, so we know each test actually tests something. Then they run against
   1.0.0. Same tests, one version property. CI runs the suite twice, once per version,
   and the 1.0.0 run asserts the loaded jar's version through the telemetry getter, so
   a misconfigured build can never pass by silently testing the downloaded old jar.
   Resolving 0.0.56 needs a GitHub Packages token; CI has one.
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

- A benchmark harness in the repo: recorded production-shaped odds changes replayed
  through decode, cache and entity build, with JMH. Budgets per message for time and
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
- Measure JAXB unmarshal cost early. If it is too slow, the generated classes stay
  and a StAX reader replaces the unmarshaller.

---

## 9. Build and release

- Root `pom.xml`, modules `odds-feed` (published), `examples` and `api-usage`
  (compile against it, not published), `system-tests`. Maven wrapper checked in.
- The schema is vendored: a copy of the schema repo's XSDs and fixtures lives in this
  repo, with the source commit recorded next to it and a script that refreshes the
  copy. The build never reaches out to another repository. Old releases stay
  rebuildable. The drift job from section 6 watches the pin.
- JDK 25 toolchain, JaCoCo, Surefire and Failsafe, Enforcer, the compatibility checks,
  the additions list, XML generation from the vendored schema.
- Version from the git tag. GitHub Actions on `v1*` tags from `next` or `main`: build,
  compatibility checks, system tests against both versions, then a pipeline step that
  queries the target registry and fails if the version already exists, then sign and
  publish. Release candidates publish automatically. A final version waits for a
  manual approval step before the Central release, because Central is irreversible.
  The GitHub Release with the jar and POM is created afterwards. Signing key and
  Central credentials live in repository secrets.
- The new line publishes `gg.oddin.oddsfeed:odds-feed` to Maven Central only. The old
  line keeps publishing `com.oddin.oddsfeed:odds-feed` to GitHub Packages only, from
  `release/0.x`. Each line's pre-release check queries its own registry. Never two
  release tags on one commit.

---

## 10. Work breakdown

Small tickets, one PR each, half a day to three days. Each has a visible result. Order
matters where it says so, the rest can run in parallel.

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

### Phase 1 – Contract and wire

8. System tests, batch two: every feed message type and the callbacks it triggers.
9. System tests, batch three: locales, invalidation, producer down and recovery,
   reconnect, REST outage, REST down at startup, authentication failure, throwing
   callback, replay, exception strategy, stale feed. Known differences from 0.0.x are
   listed, not fixed.
10. `odds-feed` module with the public entity and message types as source-compatible
    declarations, no behaviour.
11. The rest of the public API as declarations: managers, sessions, listeners,
    configuration builder. Old `examples` compile. The `api-usage` module compiles
    against both versions. The reachability test with the additions list.
    Compatibility checks run in CI.
12. Generated feed models with name-preserving bindings, decoder hardening, raw-string
    getters, plus golden decode tests. One PR per message family. Lists the types
    whose shape changed.
13. Generated REST models with name-preserving bindings plus golden tests. One PR per
    endpoint family. Same list.
14. HTTP client: all endpoints, three permit pools, one deadline per call covering
    permit, call and retries, retry for idempotent calls only, error mapping including
    permanent failures with the fatal event, 429 handling, startup deadline, API call
    events.
15. Benchmark harness: corpus, JMH skeleton, warm and cold scenarios, CI budget check.

### Phase 2 – Core

Each ticket includes its concurrency and deadlock tests. System tests turn green
group by group.

16. Cache and loader infrastructure: caches as non-blocking maps, loaders with
    single-flight and deadlines, the cache-versus-loader dependency test, bounds and
    per-locale ages, generations with tombstones, authoritative versus fill-only
    writes with the authoritatively-written marks and the per-field endpoint table,
    locale marks, clear, the long-lived feed watermark record, REST fallback after
    feed silence, stale-message write suppression, and the latch-based deadlock tests.
17. Entity caches: match and fixture.
18. Entity caches: competitor, player, tournament, sport.
19. Catalog caches: market descriptions, void reasons, match status descriptions,
    provenance-aware refresh with stale serving and maximum staleness.
20. Entity façades and factories with parallel multi-locale loading and the
    partial-failure rule.
21. AMQP layer: connection, reconnect with permanent versus transient classification
    and the three-strikes rule, connection events, one channel per session, prefetch
    validation, maximum message size, raw hand-off into bounded session queues, the
    channel replacement sequence with epochs and `SessionTransport.reset()`, the
    SDK-owned alive consumer, unparsable disposition, `open()` rollback.
22. Session dispatchers: decode, build, cache write, callback, ack, the one failure
    policy for every step; message factory, markets and outcomes; fixture-change
    deduplication with today's key; the events dispatcher with its two bounded queues.
23. Producer manager and whoami.
24. Recovery actor: single owner thread, random-seeded ids with reseed, per-producer-
    per-session checkpoints with alive-based advance and snapshot exemption, window
    clamping, per-interest completion, session open and close, coalescing, re-issue
    with cap and re-arm, the safety net with per-producer offsets, stale-offset
    disable, pause during recovery, request-before-reset, its own cap and the lagging
    state, producer-status reasons.
25. Replay manager, including the mixed-instance rule (difference 7).
26. `OddsFeed` façade, sessions, builder, one-shot lifecycle with all-or-nothing
    `open()`, watchdog over every thread group, `getHealth()` with the full counter
    list.

### Phase 3 – Parity and polish

27. Field parity with the Go SDK, in small groups.
28. Option and method parity.
29. Telemetry headers and client properties.
30. Logging cleanup. Noisy logs are a client complaint.
31. README, examples, integration guide with the onboarding checklist (distinct node
    ids, prefetch versus queue limit), FAQ update.
32. Sweep the Go and .NET SDK history since this document for fixes to port.

### Phase 4 – Release

33. Maven Central pipeline: claim the `gg.oddin` namespace, signing, registry check
    step, tag-driven publish with manual approval for finals. Also publish a last
    0.0.x version whose POM only relocates to the new coordinates, so a client who
    forgets to change the dependency is told by their own build.
34. First release candidate, soak on the test environment, candidates to clients.
35. Fix round.
36. End-of-life notice for 0.0.x sent to all clients, 1.0.0 released.

Critical path: 3 to 6, then 10, then 16, then 17 to 22, then 24, then 26, then 34. The
benchmark, the Central pipeline and the `release/0.x` cut fit into gaps.

---

## 11. Risks

- **Timeline.** Two weeks went to the hotfix already. If the schedule slips, the
  release candidate goes out later, not with fewer tests.
- **Silent behaviour differences.** Clients depend on things we do not know about.
  The system tests against 0.0.56 are our best defence. Release candidates to clients
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
  release notes and a ready-made dependency ban in the upgrade guide are the answer.
  The upside of the split: nothing offers 1.0.0 to a Java 8 client as a version bump.
- **Shared node ids.** Two instances configured with the same node id can confuse each
  other's recoveries. The SDK cannot detect it. Documentation and the onboarding
  checklist carry the rule.
- **Operator queue limit below prefetch.** Silent drop-head loss the SDK cannot see.
  The onboarding checklist carries the rule.
- **JAXB speed.** Measured in Phase 1. Fallback is a StAX reader.
- **Two Java 8 clients.** They cannot use 1.0. The old line covers them until the
  end date. Anything beyond that is a business decision, not a technical one.
- **Two lines to maintain.** Every wire change is done twice until the old line ends.

---

## 12. Open questions

1. JAXB or StAX for decoding? Decide with numbers from ticket 15.
2. Which clients test the release candidates? Needs an answer from customer success.
3. Do the priority-split session interests keep exactly today's semantics? Proposal:
   yes, they are public API.
4. Does the feed define `bet_stop` at all? If not, the type stays as legacy and is
   documented as never sent.

Resolved since the first draft: the generated XML classes are public and keep their
old names (section 3, difference 4).

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
