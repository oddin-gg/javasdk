# Java SDK 1.0 – design plan

Status: draft, waiting for review.

This document describes how we rebuild the Java SDK. The short version: same public
API, same Maven coordinates, new insides. Pure Java 25, no Kotlin, no RxJava, tests
everywhere, and a release on Maven Central.

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
| 3 | Same packages, same coordinates `com.oddin.oddsfeed:odds-feed` | Upgrade is a version bump. Docs and links keep working. |
| 4 | First version is 1.0.0 | Clear line between old and new. |
| 5 | Maven, multi-module | POMs stay buildable for years. Central publishing and API checks are standard plugins. Nobody on the team lives in Gradle. |
| 6 | No RxJava, no coroutines | Plain executors and virtual threads. Fewer concepts, fewer surprises. |
| 7 | `java.net.http.HttpClient` | Per instance, no global state, no dependency. |
| 8 | XML models generated from the vendored schema, with the old class names kept | One source of truth for all SDKs. Golden tests from the schema fixtures. Existing casts in client code keep working. |
| 9 | Manual ack after the listener callback returns, with prefetch | Backpressure that reaches the client code. A slow listener slows its own queue on the broker, not the JVM heap. |
| 10 | No client callback ever runs on an AMQP or timer thread | Heartbeats, alive handling and timers can never be blocked by client code. |
| 11 | No blocking wait of any kind under any lock | The only reliable way to not deadlock. Locks, single-flight waits and queue puts all count. |
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
   through our own pull request, never by surprise.
5. Internal types move to `internal` packages. Client code that imported `*Impl`
   classes, the caches or the API client directly stops compiling. Those types were
   never meant to be used and the examples never touch them.

### Behaviour that stays, and that the implementation must not "fix"

- Entity getters such as `match.getName(locale)` or `match.getCompetitors()` are
  synchronous and may fetch from REST when the cache is cold. They run on the caller's
  thread. A callback that touches a cold entity pays that latency on its own session
  only. Clients who want zero latency in callbacks use the preload options in section 5.
- `ExceptionHandlingStrategy` keeps its meaning. `THROW` propagates failures from
  getters to the caller, `CATCH` logs and returns null. Default stays `THROW`. A
  collection getter never returns a partial list: under `THROW` the first failed part
  fails the getter, under `CATCH` the whole collection is null.
- The cache is updated before the listener callback runs, so `match.getStatus()` inside
  `onOddsChange` sees the state the message carried. Same as today.
- Multi-session with the priority-split interests and the interest-combination
  validation stays exactly as today. A client may still create its own
  `SYSTEM_ALIVE_ONLY` session or a replay session next to live sessions.
- Fixture-change deduplication across sessions stays: one `fixture_change` for the same
  event and change timestamp reaches the client once within an hour, whichever session
  carried it.
- Recovery messages are delivered to the client like any other message, on every
  session whose interest matches, as today.
- Recovery methods keep returning the request id as a `Long`. A new status lookup by
  request id is added next to them, not instead of them.
- Delivery is at most once, as today. Exclusive queues die with the connection, so an
  unacknowledged message is never redelivered. The gap is closed by recovery, not by
  redelivery. Acking late buys backpressure, not at-least-once.

### How we check it

- The old `examples` module compiles against the new jar.
- A curated `api-usage` module calls every public signature once. It compiles against
  0.0.56 and against 1.0.0 in CI. This is the real source-compatibility gate.
- A reflection test walks the public entry points, collects every reachable type, and
  fails if one lives in an `internal` package or has no counterpart of the same name in
  the 0.0.56 jar. This is what keeps "reachable" honest and keeps `api-usage` from
  drifting: a type that appears in the walk but not in `api-usage` fails the test too.
- A jar diff (japicmp) against 0.0.56 runs as an advisory report with an allowlist for
  the accepted differences. It answers binary questions, not source ones, so it does
  not gate.
- Binary compatibility is not promised. A client that reaches the SDK through an
  intermediate library compiled against 0.0.x can hit `NoSuchMethodError` at runtime.
  We know of no such library. The 1.0.0 version signals the change.

---

## 4. Architecture

Four layers. Each one only talks to the one below.

```
 public API      OddsFeed, sessions, managers, entity interfaces
 core            entity façades, caches, recovery, producers, replay
 wire            feed decoder, REST client, generated XML models
 transport       AMQP connection, HTTP client
```

### Threads

There are exactly these thread groups per `OddsFeed`, and every piece of code belongs
to one of them:

| Group | Count | Runs | Never runs |
|---|---|---|---|
| AMQP I/O | owned by the AMQP client | frame reading, heartbeats | anything of ours |
| AMQP consumer | one executor per connection, sized to sessions + 1 | hand-off of raw deliveries into session queues | decode, build, cache writes, client code |
| Session dispatcher | one thread per session | decode, build, cache write, client callback, ack | nothing else |
| Alive dispatcher | one thread | alive handling, clock offset, producer liveness | client code |
| Events dispatcher | one thread | every non-message client callback: connection state, producer status, health, API call events, recovery completion | message callbacks |
| REST workers | virtual threads | HTTP calls | client code |
| Timers | one scheduled executor | scheduling only, each tick hands off | blocking work, client code |

Decision 10 in one sentence: client code runs on a session dispatcher or on the events
dispatcher, nowhere else.

### Delivery

- One AMQP connection per `OddsFeed`. One channel and one exclusive queue per session,
  as today. `basicQos(prefetch)` per channel. Prefetch is configurable in the range
  1 to 10 000, default 200. Zero is rejected, because the broker reads it as unlimited.
- The SDK always runs its own alive consumer on its own channel, whatever sessions the
  client created. It acks immediately and feeds the alive dispatcher. A client's
  `SYSTEM_ALIVE_ONLY` session, if any, is an ordinary session and does not carry
  producer liveness. The clock offset for the safety net is measured here, at
  receipt, before any queue.
- The consumer callback for a session channel does one thing: it puts the raw delivery
  (body bytes, envelope, delivery tag, channel epoch) into that session's queue. The
  queue is bounded at the prefetch count. It cannot overflow, because the broker never
  has more than `prefetch` unacknowledged messages on that channel. The hand-off never
  blocks and the consumer executor is never busy for longer than a queue put, so one
  session cannot delay another session's deliveries or the alive channel.
- The session dispatcher takes a raw delivery, decodes it, builds the message object,
  writes the feed data into the caches, runs the client callback, then acks. Order is
  preserved per session. A slow callback lets unacked messages pile up to `prefetch`
  on the broker, which then stops delivering to that queue and only that queue.
- A callback that throws is caught. The exception is logged, counted, and reported
  through a new `default` method on the global listener. The message is acked anyway:
  processing is finished, the failure belongs to the client. The dispatcher survives.
  This holds under both exception strategies.
- Undecodable messages are delivered to the unparsable-message callback and then
  acked. Nothing is ever nacked with requeue; that would loop a poison message.
- Every raw delivery carries the epoch of the channel it came from. When a channel is
  replaced (reconnect, safety-net reset, session close), the session queue is drained
  and every entry from the old epoch is discarded without ack. Acking a tag on a
  channel that did not issue it is a channel error, so the epoch check is what keeps a
  reset from turning into a loop. The discarded messages were on a queue the broker
  has already deleted; recovery covers them.
- When a session closes, its channel closes. Unacknowledged messages on that channel
  are dropped by the broker together with the exclusive queue. That is intended.
- The broker's own queue length limit is set by the operator, not by the SDK. The SDK
  does not declare `x-max-length`. What it does when a queue is behind is the safety
  net below.

### REST

- Two permit pools per `OddsFeed`. The control pool covers whoami, producers, recovery
  requests and catalog refreshes, size 4, not configurable. The data pool covers entity
  loading, default 16, configurable. Entity fan-out can never starve recovery.
- A permit is held for one HTTP call only, never across a fan-out. A match load
  releases its permit before its competitor loads acquire theirs, so nested loads
  cannot hold every permit in parents while children wait.
- Acquiring a permit waits at most the HTTP timeout. A getter therefore waits at most
  twice the HTTP timeout, once for the permit and once for the call, and that bound is
  documented on the configuration option.
- Independent calls run in parallel on virtual threads, under the data pool.
- Retries only for idempotent calls, with backoff. HTTP 429 and `Retry-After` are
  honoured. 401 and 403 are permanent: the call fails at once, nothing retries, and a
  fatal error event goes to the events dispatcher in addition to the caller's exception
  or null, so a `CATCH` client is not left with silent nulls.
- Partial failure in a parallel fan-out: `THROW` fails the getter with the first error;
  `CATCH` returns null for the whole collection. Never a short list.

### Caches

Bounds:

- Caffeine. Entity caches have a maximum size and expire after write with the same
  ages as today: match, fixture and tournament 12 hours, competitor and player
  24 hours, match status 20 minutes. Expire after write means a hot key is refreshed
  from REST at least that often, which is also the path that corrects a value the SDK
  got wrong. Nothing is unbounded. No soft references.
- Catalog caches (market descriptions, void reasons, match status descriptions, sports
  list) refresh after write. An expired entry is served while the refresh runs and
  while it fails, up to a maximum staleness of 24 hours. Past that the entry is treated
  as missing and the caller gets the exception strategy. Serving stale raises a health
  state (section on watchdog). Failed refreshes back off per locale.
- The caches hold entities, statuses and catalogs. They do not hold market state or
  odds; those live only in the messages the client receives. Eviction of a match
  status loses nothing REST cannot restore, because status and scores are in the
  summary.

Blocking rule:

- **No blocking wait of any kind while holding a lock.** Not a lock, not a
  single-flight wait, not a permit, not a queue put, not I/O. Check the cache, release,
  fetch, merge.
- Concurrent misses for the same key wait for one fetch (single-flight). The wait is
  bounded by the fetch's own bound, twice the HTTP timeout, plus a margin. Waiters that
  time out fail with the exception strategy; they do not start their own fetch, so a
  slow REST does not turn single-flight into a stampede.
- A fetch never waits on another cache's fetch. Data another cache needs is either in
  the response already, or loaded on demand later by whoever asks.
- Side-loading queues never block the producer. When full, they drop and count. A
  dropped side-load only means the data is fetched on demand later.
- The architectural test for ticket 16 fails when a cache acquires another cache's
  lock, joins another cache's single-flight, or blocks on a queue or permit while
  holding its own lock. It runs every cache pair cold and concurrently with latches.

Write rule:

- Every field of every cached entity has one **authoritative endpoint**. For a
  competitor the authoritative endpoint of its player list is the competitor profile;
  of its name per locale, the profile in that locale. For a match status it is the
  match summary. Ticket 16 carries the full table.
- A response from the authoritative endpoint replaces the fields it is authoritative
  for, verbatim, in the locale it was fetched for. A field the response omits is
  cleared. That is how a retracted winner disappears from the summary.
- A response from any other endpoint that happens to carry data for an entity (a
  summary carrying competitor names, a schedule carrying tournaments) **fills only**:
  it writes fields that are currently absent and never overwrites a present one. This
  is what makes the summary useful without a profile fetch, and what keeps a partial
  projection from erasing richer data.
- A write never starts a fetch. Writes are synchronous, short, and take only the lock
  of the cache being written.
- Every cache key has a generation counter, kept in a bounded side map sized like the
  cache and expiring after the cache's own age. Invalidation (a `fixture_change`, a
  public clear) bumps it. An authoritative fetch remembers the generation it started
  with and its result is discarded if the generation moved. Fill-only writes do not
  check generations; they can only add what is absent, and a clear makes the entry
  absent, so the worst case is a stale name that the next authoritative fetch corrects.
  The stale-result path counts and the caller of a discarded fetch re-reads the cache,
  which by then holds the fresh value or triggers a fresh fetch.

Ownership and ordering:

- Feed messages own live status, scores, period scores and the match clock. REST owns
  everything else. Market state and odds are not cached.
- Feed-owned fields carry a watermark per entity and producer: the `timestamp` of the
  last feed message that wrote them. A message from the same producer with an older
  timestamp does not write feed-owned fields. It is still built and delivered; the
  watermark orders cache writes, never delivery. Messages that carry no feed-owned
  fields (settlements, cancels, bet stops) are not watermark-checked at all.
- Feed and REST clocks are never compared. A REST snapshot writes feed-owned fields
  only when the entry has no feed watermark, or when the last feed write is older
  than the match status age (20 minutes) by the SDK's own clock. In other words, a live
  match is owned by the feed; a match the feed has gone quiet on falls back to REST.
  The `generated_at` attribute is optional on the wire and is not used for ordering.
- Within a feed message, a missing optional scalar means "keep what you have", never
  "reset to zero". Both schemas mark scores optional.
- After eviction an entry is cold, has no watermark, and the next read takes REST as
  authoritative. That is today's behaviour on a cache miss.
- Fixture-change deduplication is one shared, concurrent map per `OddsFeed`, keyed by
  event id and change timestamp, bounded in size and expiring after one hour. All
  session dispatchers consult it before delivering.
- Caches belong to one `OddsFeed` instance. A replay session created next to live
  sessions on the same instance shares those caches and producer state, as today; the
  isolation a client gets from a separate replay instance is documented as the
  recommended setup, not enforced.

Locales and catalogs:

- Loaded-locale marks are stored with the values they describe and share their
  lifetime. A mark cannot outlive its values, and values cannot outlive their mark.
  New sports, tournaments and markets show up without a restart.
- Catalog entries carry their provenance: bulk-listed or individually fetched. A
  refresh of a list endpoint replaces the bulk-listed entries for that locale and
  leaves individually fetched ones (dynamic market variants) alone; those expire on
  their own age. A market or tournament removed upstream disappears on the next
  refresh.
- Every cache has a public clear method.

### Recovery and producers

- Recovery is a state machine per producer, with tests that drive it through every
  transition.
- Request ids start from a random 31-bit seed per process, as today, and increase by
  one. The seed avoids a restart within the same second reusing ids. Ids are scoped by
  the configured node id. A completion for an id the SDK did not issue, or has already
  closed, is ignored and logged. Two instances sharing one node id can still collide;
  the documentation requires distinct node ids per instance and the SDK logs a warning
  when a completion arrives for an id it issued but on a request it did not send.
- The recovery checkpoint is kept **per producer and per session**: each session
  records the timestamp of the last message it finished for each producer. A recovery
  for a producer starts from the oldest checkpoint among the sessions that receive
  that producer, so a session that lags behind never has its interval skipped. A
  client-supplied recovery-from timestamp (existing setter) seeds all of them.
- The recovery-from point is clamped to the producer's stateful recovery window, as
  today. A cold start with no seed requests a full snapshot. A client-supplied
  timestamp older than the window is clamped and logged.
- Snapshot completion is tracked per message interest, as today: a producer is up
  again when every session that receives it has seen its `snapshot_complete`.
- Recovery that times out is re-issued with backoff, at most three times in a row.
  After that the producer stays down and the client gets a producer-status event with
  the reason. The cap re-arms when an alive from that producer arrives after a gap, so
  an outage longer than the retry window does not leave a producer down forever once
  it is healthy again.
- The safety net. Message rates depend on what a client has booked, and the SDK does
  not promise to keep up with every queue. Backpressure protects the JVM and the
  broker connection. The safety net bounds how far behind a client can fall: past a
  point, one recovery snapshot is cheaper than processing a long stale backlog message
  by message. The rule, per session:
  - Age of a message is its producer timestamp against the SDK clock corrected by the
    offset measured on the alive channel, which is never backpressured. Age is sampled
    when the dispatcher takes the message, so it includes both broker backlog and the
    session's own queue.
  - When the age stays above the configured limit for the configured window, the
    session's channel is replaced: a fresh exclusive queue, the old backlog dropped by
    the broker, the session queue drained by epoch. Then a recovery is requested for
    every producer the session receives, from the oldest checkpoint as above.
  - The net is suspended for a session while a recovery it triggered is in progress,
    and it ignores messages that carry a recovery request id, so a large snapshot
    cannot trigger the net that asked for it.
  - It backs off between resets and shares the recovery cap. When the cap is spent the
    net stops resetting: messages keep flowing under backpressure, the producer is
    marked down with reason "consumer too slow", and a health event is raised. That
    is the arbitration between backpressure and the net: backpressure always wins in
    the end, the net gets three tries to shortcut it.
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
- Authentication and authorisation failures and a wrong virtual host are permanent.
  They stop the reconnect loop and surface as a fatal error event with the broker's
  reason.
- Broker resource limits (connections, queues) are transient. They are retried with a
  long backoff and surface as an error event each time, never as a silent hang.

### Wire

- XML models are generated from the vendored schema. Binding customisations keep the
  old class names, packages and getters (section 3).
- The decoder is the untrusted-input boundary. DTDs off, external entities off, and a
  maximum document size that bounds parser work. The body has already been received
  as one byte array by the time the decoder sees it; the AMQP frame limit and the
  fact that only our own producers publish to these queues bound the allocation. One
  malformed document costs one unparsable callback, nothing more.
- Unknown enum values decode to an `UNKNOWN` constant, and the message keeps the raw
  string in a separate getter next to the enum getter. Unknown attributes and elements
  are ignored in production and fail the golden tests, so producer drift shows up in
  CI, not at a client.

### Watchdog and health

The SDK checks itself. It watches the consumer executor, every session dispatcher, the
events dispatcher, and `ThreadMXBean` for monitor deadlocks. A dispatcher inside one
callback for longer than the configured limit, an executor that has not moved, or a
reported deadlock produces a loud log line, a health event on the global listener (new
`default` method), and a state change in `getHealth()`.

`getHealth()` also exposes the degraded-but-running counters the design deliberately
creates: dropped side-loads, catalogs served stale and for how long, unparsable
messages, callback exceptions, discarded stale fetches, and per-session queue depth.
Silent degradation is a log line plus a counter, never only a log line.

Remediation is limited and stated: the watchdog does not kill threads. A wedged
dispatcher is reported; the session can be closed and rebuilt by the client, and
`close()` on the feed uses the shutdown timeout so a wedged callback cannot block
shutdown. A stall must never be silent again, but the SDK cannot unwedge client code.

---

## 5. New in 1.0

Additive only. All of it exists in the Go SDK already.

- Entities: `Category` on tournaments, reference ids on matches and tournaments,
  `Statistics` on match status, `IconPath` and `Abbreviation`, competitor ids on
  tournaments, tournament ids on sports.
- Getters: single `Sport`, `Tournament`, `Player` by id, `ProducersInScope`,
  `ProducerStatus`, replay status, recovery status by request id, `getHealth()`.
- Cache control: clear methods per entity type, reload of void reasons.
- Configuration: default locale, preload locales, eager entity preload for messages,
  HTTP timeout, prefetch, REST concurrency limit, max inactivity, max recovery time,
  stale-message limit and window, exchange names, shutdown timeout, API call logging.
- Events on the global listener, all as `default` methods: connection state changes,
  health events, listener exceptions, fatal REST errors, API call events with method,
  URL, status and latency, producer-status reasons that name the cause.
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

What the old line gets until its end date: critical fixes, and additive wire fields
(a new XML attribute the producers start sending). Nothing else.

How the two lines stay in sync on the wire: each line vendors the schema at a pinned
commit and decodes its fixtures in its own tests, so a line that moves its pin proves
it decodes the new fixture. A line that does not move its pin stays green on its old
copy, so the pin itself needs watching: a scheduled CI job in this repo compares both
lines' pinned commits with the schema repo's head and fails when either line lags by
more than seven days. A schema bump is one PR per line, opened together. The PR
template checkbox is a reminder; the drift job is the control.

Timeline we communicated: test builds in October and November 2026, release at the
end of November or beginning of December 2026.

---

## 7. Tests

Three layers. No ticket is done without its tests.

1. **System tests, written first.** Black-box, public API only. A real RabbitMQ in a
   container with a publisher that replays fixture messages, and a fake REST server
   serving the schema fixtures. They assert what a client can observe: which callbacks
   fire, entity values, locale handling, invalidation on fixture change, producer down
   and recovery, reconnect, REST outage, authentication failure, stale feed, a
   callback that throws, replay, exception strategy. They run against 0.0.56 first, so
   we know each test actually tests something. Then they run against 1.0.0. Same
   tests, one version property. CI runs the suite twice, once per version, and the
   1.0.0 run asserts the loaded jar's version through the telemetry getter, so a
   misconfigured build can never pass by silently testing the downloaded old jar.
   Resolving 0.0.56 needs a GitHub Packages token; CI has one.
2. **Unit and concurrency tests with every ticket.** Golden decode tests for every
   message and endpoint from the schema fixtures. Cache tests: expiry, eviction,
   locale fill-in, clear, single-flight, generation counter, fill-only versus
   authoritative writes, watermark ordering, REST fallback after feed silence. The
   blocking-rule test from section 4. Deadlock tests: concurrent cold loads of every
   cache pair with latches, asserting no deadlocked threads and completion in time.
   Recovery state machine tests including per-session checkpoints, the caps and the
   re-arm. Safety-net tests including the snapshot exemption and the cap arbitration.
   Channel-epoch tests for every replacement path. Lifecycle races: open, close,
   reconnect.
3. **Soak and client tests last.** Real test broker, replay of recorded traffic,
   release candidates to clients who volunteered.

Two rules from the last hotfix review: a regression test must be shown to fail on
the broken code before it counts, and a test that cannot fail is a bug.

---

## 8. Performance

Performance is a requirement, not a follow-up.

- A benchmark harness in the repo: recorded production-shaped odds changes replayed
  through decode, cache and entity build, with JMH. Budgets per message for time and
  allocation. Runs in CI as a regression check.
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
  XML generation from the vendored schema.
- Version from the git tag. GitHub Actions on `v1*` tags from `next` or `main`: build,
  compatibility checks, system tests against both versions, then a pipeline step that
  queries the target registry and fails if the version already exists, then sign and
  publish. Release candidates publish automatically. A final version waits for a
  manual approval step before the Central release, because Central is irreversible.
  The GitHub Release with the jar and POM is created afterwards. Signing key and
  Central credentials live in repository secrets.
- The new line publishes to Maven Central only. The old line publishes to GitHub
  Packages only, from `release/0.x`. Each line's pre-release check queries its own
  registry. Never two release tags on one commit.

---

## 10. Work breakdown

Small tickets, one PR each, half a day to three days. Each has a visible result. Order
matters where it says so, the rest can run in parallel.

### Phase 0 – Foundation

1. This design doc approved.
2. `next` branch, root POM, Maven wrapper, JDK 25, empty CI green. Only the
   `system-tests` module exists. It depends on the SDK by coordinate, so switching
   between the old and the new SDK is one version property.
3. Vendor the schema: copy XSDs and fixtures into the repo with the source commit
   recorded and a refresh script. Everything below reads them.
4. Fake REST server for tests, serving the schema fixtures.
5. Fake feed for tests: RabbitMQ in a container plus a publisher that replays fixture
   messages. An in-process fake is not possible, the old SDK opens a real connection.
6. First system tests green against 0.0.56: open, receive an odds change, read a
   match, close. Includes the JAXB runtime the old jar needs on a modern JDK.
7. Cut `release/0.x` once 0.0.56 is tagged, keep its Java 8 CI green, add the
   dual-line checkbox to the PR template and the schema drift job.

### Phase 1 – Contract and wire

8. System tests, batch two: every feed message type and the callbacks it triggers.
9. System tests, batch three: locales, invalidation, producer down and recovery,
   reconnect, REST outage, authentication failure, throwing callback, replay,
   exception strategy, stale feed. Known differences from 0.0.x are listed, not fixed.
10. `odds-feed` module with the public entity and message types as source-compatible
    declarations, no behaviour.
11. The rest of the public API as declarations: managers, sessions, listeners,
    configuration builder. Old `examples` compile. The `api-usage` module compiles
    against both versions. The reachability test. Compatibility checks run in CI.
12. Generated feed models with name-preserving bindings, decoder hardening, raw-string
    getters, plus golden decode tests. One PR per message family. Lists the types
    whose shape changed.
13. Generated REST models with name-preserving bindings plus golden tests. One PR per
    endpoint family. Same list.
14. HTTP client: all endpoints, two permit pools with acquire timeout, retry for
    idempotent calls only, timeouts, error mapping including permanent failures with
    the fatal event, 429 handling, API call events.
15. Benchmark harness: corpus, JMH skeleton, CI budget check.

### Phase 2 – Core

Each ticket includes its concurrency and deadlock tests. System tests turn green
group by group.

16. Cache infrastructure: bounds and ages, single-flight with bounded wait, generation
    counters in a bounded side map, authoritative versus fill-only writes with the
    per-field endpoint table, per-locale fill-in with shared lifetime, clear, feed
    watermarks per producer, REST fallback after feed silence, and the architectural
    test for the blocking rule.
17. Entity caches: match and fixture.
18. Entity caches: competitor, player, tournament, sport.
19. Catalog caches: market descriptions, void reasons, match status descriptions,
    provenance-aware refresh with stale serving and maximum staleness.
20. Entity façades and factories with parallel multi-locale loading and the
    partial-failure rule.
21. AMQP layer: connection, reconnect with permanent versus transient classification,
    connection events, one channel per session, prefetch validation, raw hand-off into
    bounded session queues, channel epochs, the SDK-owned alive consumer, unparsable
    disposition.
22. Session dispatchers: decode, build, cache write, callback, ack, throwing-callback
    policy; message factory, markets and outcomes; fixture-change deduplication; the
    events dispatcher.
23. Producer manager and whoami.
24. Recovery state machine: random-seeded ids, per-producer-per-session checkpoints,
    window clamping, per-interest completion, re-issue with cap and re-arm, the safety
    net with clock correction, snapshot exemption and cap arbitration, producer-status
    reasons.
25. Replay manager.
26. `OddsFeed` façade, sessions, builder, idempotent lifecycle, watchdog, `getHealth()`
    with the degradation counters.

### Phase 3 – Parity and polish

27. Field parity with the Go SDK, in small groups.
28. Option and method parity.
29. Telemetry headers and client properties.
30. Logging cleanup. Noisy logs are a client complaint.
31. README, examples, integration guide, FAQ update.
32. Sweep the Go and .NET SDK history since this document for fixes to port.

### Phase 4 – Release

33. Maven Central pipeline: namespace, signing, registry check step, tag-driven
    publish with manual approval for finals.
34. First release candidate, soak on the test environment, candidates to clients.
35. Fix round.
36. End-of-life notice for 0.0.x sent to all clients, 1.0.0 released.

Critical path: 3 to 6, then 10, then 16, then 17 to 22, then 26, then 34. The
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
- **Same coordinates on two registries.** Dependency tooling that watches Maven Central
  will offer 1.0.0 to the Java 8 clients as an upgrade. Their build fails loudly on the
  class-file version; nothing runs on the wrong SDK. Release notes and the end-of-life
  notice say so.
- **Shared node ids.** Two instances configured with the same node id can confuse each
  other's recoveries. The SDK can warn, not prevent. Documentation and the client
  onboarding checklist carry the rule.
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
- 2026-09-21: second automated review of the revised text, 59 findings kept. The
  delivery model now hands raw bytes off the consumer executor and does everything
  else on the session dispatcher; the SDK owns the alive consumer; every client
  callback has a named thread; throwing callbacks are acked; channel epochs fence
  replaced channels; two REST permit pools with acquire timeouts and no nested
  permits; authoritative versus fill-only cache writes replace "clear on absence";
  feed and REST clocks are no longer compared, REST takes over after feed silence;
  watermarks per producer and only for the fields a message carries; entity caches
  expire after write again; catalog refresh respects provenance and has a maximum
  staleness; generation counters live in a bounded side map; recovery checkpoints are
  per producer and per session with window clamping and per-interest completion;
  recovery ids are random-seeded again; the recovery cap re-arms; the safety net is
  suspended during its own snapshot and yields to backpressure after the cap; broker
  limits are transient; `UNKNOWN` enums get a raw-string getter; the size limit claim
  is corrected; degradation counters are exposed through `getHealth()`; system tests
  run against both versions and assert the loaded one; the dual-line control is a
  drift job, not the fixtures alone; accepted difference 5 for internal package moves;
  reflection test enforces reachability; risks for shared coordinates and node ids.
