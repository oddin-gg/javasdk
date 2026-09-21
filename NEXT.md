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
| 8 | XML models generated from the shared schema, with the old class names kept | One source of truth for all SDKs. Golden tests from the schema fixtures. Existing casts in client code keep working. |
| 9 | Manual ack after the listener callback returns, with prefetch | Backpressure that reaches the client code. A slow listener slows its own queue on the broker, not the JVM heap. |
| 10 | Listener callbacks never run on the AMQP thread | Heartbeats and the alive channel can never be blocked by client code. |
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
XML schema classes handed out by the extended listener. Internal is the rest, named
explicitly: every `*Impl` class, the `cache` package, the `di` package, `ApiClient`,
`ChannelConsumer`, `DispatchManager`, `TaskManager`, `WhoAmIManager`. Kotlin made all
of it public. The new code puts internal types in packages whose name contains
`internal`, and the compatibility check ignores those packages.

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

### Behaviour that stays, and that the implementation must not "fix"

- Entity getters such as `match.getName(locale)` or `match.getCompetitors()` are
  synchronous and may fetch from REST when the cache is cold. They run on the caller's
  thread with the configured HTTP timeout. They never run on the AMQP thread, because
  callbacks do not run there either. A callback that touches a cold entity pays that
  latency on its own session only. Clients who want zero latency in callbacks use the
  preload options in section 5.
- `ExceptionHandlingStrategy` keeps its meaning. `THROW` propagates failures from
  getters to the caller, `CATCH` logs and returns null. Default stays `THROW`.
- Multi-session with the priority-split interests and the interest-combination
  validation stays exactly as today.
- Fixture-change deduplication across sessions stays: one `fixture_change` for the same
  event and change timestamp reaches the client once, whichever session carried it.
- Recovery methods keep returning the request id as a `Long`. A new status lookup by
  request id is added next to them, not instead of them.
- Delivery is at most once, as today. Exclusive queues die with the connection, so an
  unacknowledged message is never redelivered. The gap is closed by recovery, not by
  redelivery. Acking late buys backpressure, not at-least-once.

### How we check it

- The old `examples` module compiles against the new jar.
- A curated `api-usage` module calls every public signature once. It compiles against
  0.0.56 and against 1.0.0 in CI. This is the real source-compatibility gate.
- A jar diff (japicmp) against 0.0.56 runs as an advisory report with an allowlist for
  the four accepted differences. It answers binary questions, not source ones, so it
  does not gate.
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

### Threading and delivery

- One AMQP connection per `OddsFeed`. One channel and one exclusive queue per session,
  as today. `basicQos(prefetch)` per channel, configurable, default 200.
- The consumer callback for a channel decodes the message, builds the message object,
  and hands it to that session's dispatcher queue. The queue is bounded at the
  prefetch count. It cannot overflow, because the broker never has more than
  `prefetch` unacknowledged messages on that channel. The hand-off never blocks.
- The session dispatcher is one thread per session. It runs the client callback, then
  acks the message. Order is preserved per session. A slow callback lets unacked
  messages pile up to `prefetch` on the broker, which then stops delivering to that
  queue and only that queue. Other sessions, and the alive channel, keep flowing.
  Heartbeats live on the connection's own thread.
- Undecodable messages are delivered to the unparsable-message callback and then
  acked. Nothing is ever nacked with requeue; that would loop a poison message.
- When a session closes, its channel closes. Unacknowledged messages on that channel
  are dropped by the broker together with the exclusive queue. That is intended.
- Blocking REST work runs on virtual threads. Independent calls run in parallel, for
  example all competitors of a match. One semaphore per `OddsFeed` bounds all
  outbound REST calls together, default 16, configurable. Per-call fan-out limits do
  not add up to a global one, so the global one is the one that matters.
- HTTP 429 and `Retry-After` are honoured with backoff. 401 and 403 are permanent:
  the call fails at once, the error is surfaced, nothing retries.
- One `ScheduledExecutorService` for timers. No task ever blocks on it.

### Caches

Bounds:

- Caffeine. Entity caches (match, fixture, competitor, player, tournament, sport,
  match status) have a maximum size and expire after access. Nothing is unbounded.
  No soft references.
- Catalog caches (market descriptions, void reasons, match status descriptions,
  sports list) refresh after write. An expired entry is served while the refresh runs
  and while it fails. Failed refreshes back off per locale. A cold catalog with REST
  down fails the caller according to the exception strategy, as today.
- An evicted live match status is rebuilt from the REST summary on the next read.
  Status and scores are in the summary, so eviction loses no state that REST cannot
  restore. Only the feed's ordering watermark is lost, and a fresh summary resets it.

Blocking rule:

- **No blocking wait of any kind while holding a lock.** Not a lock, not a
  single-flight wait, not a queue put, not I/O. Check the cache, release, fetch, merge.
- Concurrent misses for the same key wait for one fetch (single-flight). That wait has
  a timeout equal to the HTTP timeout plus a margin, so a stuck fetch cannot hang
  waiters forever.
- A fetch never waits on another cache's fetch. Data another cache needs is either in
  the response already, or loaded on demand later by whoever asks.
- Side-loading queues never block the producer. When full, they drop and count. A
  dropped side-load only means the data is fetched on demand later.
- The architectural test for ticket 16 fails when a cache acquires another cache's
  lock, joins another cache's single-flight, or blocks on a queue while holding its
  own lock. It runs every cache pair cold and concurrently with latches.

Write rule:

- A REST response is written into **every** cache it carries data for. A match
  summary populates the match, the competitors it embeds, the tournament and the
  status. That is data already in hand; not writing it means fetching it again.
- A write never starts a fetch. The 0.0.49 fan-out was fetches inside observers, not
  writes. Writes are synchronous, short, and take only the lock of the cache being
  written.
- Every cache key has a generation counter. Invalidation (a `fixture_change`, a public
  clear) bumps it. A fetch remembers the generation it started with and its result is
  discarded if the generation moved. Without this, a fetch that started before a
  clear repopulates the key after it.

Ownership and ordering:

- Fields have an owner. Feed messages own live status, scores, period scores, market
  state and odds. REST owns fixture data, competitors, tournament, and the winner.
- Feed writes are ordered by the message `timestamp` attribute, per entity. An entry
  keeps the timestamp of the last feed message it applied. An older message is a
  no-op. This orders live messages against recovery snapshots and against each other
  when several sessions carry the same match. Both timestamps come from the producer.
- A REST snapshot applies to feed-owned fields only if its `generated_at` is newer
  than the entry's feed watermark. REST-owned fields are replaced verbatim from every
  snapshot, so a field the snapshot omits is cleared. That is how a retracted winner
  disappears. Both clocks are server-side.
- Within a feed message, a missing optional scalar means "keep what you have", never
  "reset to zero". Both schemas mark scores optional. Ticket 16 carries the per-field
  table that says, for each field, who owns it and whether absence means keep or clear.
- Fixture-change deduplication is one shared, concurrent map per `OddsFeed`, keyed by
  event id and change timestamp, entries expire after one hour. All session
  dispatchers consult it.
- Caches belong to one `OddsFeed` instance. A replay feed is a separate instance with
  its own caches, so replay never writes into a live cache.

Locales and catalogs:

- Loaded-locale marks are stored with the values they describe and share their
  lifetime. A mark cannot outlive its values, and values cannot outlive their mark.
  New sports, tournaments and markets show up without a restart.
- Catalog list endpoints replace the collection on refresh, they do not merge into it.
  A market or tournament removed upstream disappears on the next refresh.
- Every cache has a public clear method.

### Recovery and producers

- Recovery is a state machine per producer, with tests that drive it through every
  transition.
- Request ids start at the current time in seconds when the process starts and
  increase by one. They are scoped by the node id the client configured. A completion
  for an id the SDK did not issue, or has already closed, is ignored and logged. This
  keeps a stale `snapshot_complete` from before a restart from closing a new recovery.
- The recovery-from point per producer lives in memory. The client may seed it before
  `open()` through the existing setter. The SDK does not persist it; a client that
  needs a recovery point across restarts stores the last processed timestamp itself,
  as today.
- Recovery that times out is re-issued with backoff, at most three times. After that
  the producer stays down and the client gets a producer-status event saying so.
  Nothing loops forever.
- Alive gaps and processing delays are detected and recovered from without a restart.
- Stale-message safety net. Message rates depend on what a client has booked, so the
  SDK does not promise to keep up with every queue. It promises to notice when it has
  not. The rule: if the age of consumed messages stays above the configured limit for
  the configured window, the session's channel is closed, a fresh exclusive queue is
  declared, and recovery runs from the last processed timestamp. Closing the channel
  discards the broker-side backlog on purpose; recovery replaces it. The trigger uses
  the producer timestamp against a client clock corrected by the offset measured on
  `alive` messages, with a tolerance, so a skewed client clock does not fire it. It
  backs off between resets and shares the recovery cap above. It is off for replay
  sessions, whose messages are old by design.
- Unknown producer ids are an error, not a fabricated producer.

### Connection

- Connection events: connecting, up, down, recovering. No duplicate "down" on a normal
  close.
- Reconnect with backoff on network failures. Exclusive queues are always re-declared;
  whatever the broker buffered for the old queue is gone, and recovery covers it.
- Authentication and authorisation failures, wrong virtual host, and broker limits
  (connections, queues) are permanent. They stop the reconnect loop and surface as a
  fatal error event with the broker's reason. Never a silent retry.

### Wire

- XML models are generated from the pinned schema. Binding customisations keep the
  old class names, packages and getters (section 3).
- The decoder is the untrusted-input boundary. DTDs off, external entities off,
  maximum document size enforced. One malformed document costs one unparsable
  callback, nothing more.
- Unknown enum values decode to an `UNKNOWN` constant that keeps the raw string.
  Unknown attributes and elements are ignored in production and fail the golden tests,
  so producer drift shows up in CI, not at a client.

### Watchdog

The SDK checks itself. It watches the AMQP consumer threads, every session dispatcher,
and `ThreadMXBean` for monitor deadlocks. A dispatcher that has been inside one
callback for longer than the configured limit, a consumer thread that has not moved,
or a reported deadlock produces a loud log line, a health event on the global listener
(new `default` method), and a `getHealth()` state the client can poll. A stall must
never be silent again.

---

## 5. New in 1.0

Additive only. All of it exists in the Go SDK already.

- Entities: `Category` on tournaments, reference ids on matches and tournaments,
  `Statistics` on match status, `IconPath` and `Abbreviation`, competitor ids on
  tournaments, tournament ids on sports.
- Getters: single `Sport`, `Tournament`, `Player` by id, `ProducersInScope`,
  `ProducerStatus`, replay status, recovery status by request id.
- Cache control: clear methods per entity type, reload of void reasons.
- Configuration: default locale, preload locales, eager entity preload for messages,
  HTTP timeout, prefetch, REST concurrency limit, max inactivity, max recovery time,
  stale-message limit and window, exchange names, shutdown timeout, API call logging.
- Events: connection state changes, health events from the watchdog, API call events
  with method, URL, status and latency, producer-status reasons that name the cause.
- Raw data: `default` methods on the extended listener delivering raw XML bytes for
  feed messages and REST responses.
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

How the two lines stay in sync on the wire: both decode the same fixtures from the
same pinned schema commit in their tests. A schema bump is one PR per line that moves
the pin and adds the fixture. If one line lags, its golden tests go red on the new
fixture. The PR template checkbox is a reminder, the fixtures are the control.

Timeline we communicated: test builds in October and November 2026, release at the
end of November or beginning of December 2026.

---

## 7. Tests

Three layers. No ticket is done without its tests.

1. **System tests, written first.** Black-box, public API only. A real RabbitMQ in a
   container with a publisher that replays fixture messages, and a fake REST server
   serving the schema fixtures. They assert what a client can observe: which callbacks
   fire, entity values, locale handling, invalidation on fixture change, producer down
   and recovery, reconnect, REST outage, authentication failure, stale feed, replay,
   exception strategy. They run against 0.0.56 first, so we know each test actually
   tests something. Then they run against 1.0.0. Same tests, one version property.
   Resolving 0.0.56 needs a GitHub Packages token; CI has one.
2. **Unit and concurrency tests with every ticket.** Golden decode tests for every
   message and endpoint from the schema fixtures. Cache tests: expiry, eviction,
   locale fill-in, clear, single-flight, generation counter, ordering by timestamp.
   The blocking-rule test from section 4. Deadlock tests: concurrent cold loads of
   every cache pair with latches, asserting no deadlocked threads and completion in
   time. Recovery state machine tests including the caps. Lifecycle races: open, close,
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
  under the global REST semaphore.
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
  rebuildable.
- JDK 25 toolchain, JaCoCo, Surefire and Failsafe, Enforcer, the compatibility checks,
  XML generation from the vendored schema.
- Version from the git tag. GitHub Actions on `v1*` tags from `next` or `main`: build,
  compatibility checks, then a pipeline step that queries the target registry and
  fails if the version already exists, then sign and publish. Release candidates
  publish automatically. A final version waits for a manual approval step before the
  Central release, because Central is irreversible. The GitHub Release with the jar and
  POM is created afterwards. Signing key and Central credentials live in repository
  secrets.
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
   dual-line checkbox to the PR template.

### Phase 1 – Contract and wire

8. System tests, batch two: every feed message type and the callbacks it triggers.
9. System tests, batch three: locales, invalidation, producer down and recovery,
   reconnect, REST outage, authentication failure, replay, exception strategy, stale
   feed. Known differences from 0.0.x are listed, not fixed.
10. `odds-feed` module with the public entity and message types as source-compatible
    declarations, no behaviour.
11. The rest of the public API as declarations: managers, sessions, listeners,
    configuration builder. Old `examples` compile. The `api-usage` module compiles
    against both versions. Compatibility checks run in CI.
12. Generated feed models with name-preserving bindings, decoder hardening, plus
    golden decode tests. One PR per message family. Lists the types whose shape
    changed.
13. Generated REST models with name-preserving bindings plus golden tests. One PR per
    endpoint family. Same list.
14. HTTP client: all endpoints, retry for idempotent calls only, timeouts, error
    mapping including permanent failures, 429 handling, global semaphore, API call
    events.
15. Benchmark harness: corpus, JMH skeleton, CI budget check.

### Phase 2 – Core

Each ticket includes its concurrency and deadlock tests. System tests turn green
group by group.

16. Cache infrastructure: bounds, single-flight with timeout, generation counters,
    per-locale fill-in with shared lifetime, clear, the per-field ownership table,
    ordering by feed timestamp, and the architectural test for the blocking rule.
17. Entity caches: match and fixture.
18. Entity caches: competitor, player, tournament, sport.
19. Catalog caches: market descriptions, void reasons, match status descriptions,
    refresh-after-write with stale serving.
20. Entity façades and factories with parallel multi-locale loading.
21. AMQP layer: connection, reconnect with permanent-failure detection, connection
    events, one channel per session, prefetch, ack after callback, routing keys,
    unparsable disposition.
22. Message factory, markets and outcomes, session dispatchers, fixture-change
    deduplication.
23. Producer manager and whoami.
24. Recovery state machine: time-seeded ids, re-issue with cap, stale-message safety
    net with clock correction, producer-status reasons.
25. Replay manager.
26. `OddsFeed` façade, sessions, builder, idempotent lifecycle, watchdog with health
    events and getter.

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
- 2026-09-21: automated three-reviewer design review, 47 findings kept by the judge.
  Addressed in sections 3, 4, 6, 7, 9 and 10: ack after callback with per-session
  channels, blocking rule widened beyond locks, write rule rewritten, generation
  counters, feed ordering by timestamp, time-seeded recovery ids, recovery caps,
  safety-net clock correction and replay exemption, global REST semaphore and 429
  handling, permanent-failure detection, decoder hardening, watchdog scope and health
  events, public API defined by reachability, generated classes keep their names,
  recovery return type kept, real source-compatibility gate, fixture-based dual-line
  control, vendored schema, release pipeline gates.
