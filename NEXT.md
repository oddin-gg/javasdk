# Java SDK 1.0 – design plan

Status: draft, waiting for review.

This document describes how we rebuild the Java SDK. The short version: same public
API, same Maven coordinates, new insides. Pure Java 25, no Kotlin, no RxJava, tests
everywhere, and a release on Maven Central.

The document is meant to be read in ten minutes. Details that only matter to the
person implementing a ticket go into that ticket, not here.

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
| 8 | XML models generated from the shared schema repo at build time | One source of truth for all SDKs. Golden tests from the schema fixtures. |
| 9 | Manual ack with prefetch | Backpressure. A slow listener slows the broker, not the JVM heap. |
| 10 | Listener callbacks never run on the AMQP thread | Heartbeats and the alive channel can never be blocked by client code. |
| 11 | No I/O under any lock | The only reliable way to not deadlock. |
| 12 | Old 0.0.x line stays supported until 31 March 2027 | Two clients are still on Java 8. Critical fixes only. |
| 13 | System tests first, before any new code | They run against the old and the new SDK. They are the proof that nothing broke. |
| 14 | Sidecar is on hold | Some clients do not want to run our binary. Keep the API layer separable so it stays possible later. |

Open decisions are in section 12.

---

## 3. What stays the same for clients

We promise **source compatibility** of the public API. Client code that compiles
against 0.0.x compiles against 1.0.0. Behaviour stays the same unless it was a bug.

The public API is:

- `OddsFeed`, `OddsFeedConfiguration` and its builder, `Environment`, `Region`,
  `ExceptionHandlingStrategy`
- Managers: `SportsInfoManager`, `MarketDescriptionManager`, `ProducerManager`,
  `RecoveryManager`, `ReplayManager`
- Sessions: `OddsFeedSession`, `ReplaySession`, `OddsFeedSessionBuilder`, `MessageInterest`
- Listeners: `GlobalEventsListener`, `OddsFeedListener`, `OddsFeedExtListener`
- Entities: everything under `api/entities` and `mq/entities`, `URN`, `MessageTimestamp`
- Exceptions

Everything else is internal: `*Impl` classes, caches, the DI module, the dispatch
manager, the API client. Kotlin made all of it public. We will mark the internal
packages clearly and exclude them from the compatibility check.

Things we know are different and accept:

- Kotlin `data class` extras (`copy()`, `componentN()`) disappear. Nobody should be
  using them from Java.
- `MarketMessage.getMarkets()` becomes `List<? extends Market>`. Kotlin allowed the
  covariant override, Java does not. Code that assigns the result to `List<Market>`
  needs one cast.
- Getters for attributes the feed never sends stay, are marked `@Deprecated`, and
  return null. Removing them would break compilation for someone.
- The old XML schema classes (`OF*`, `RA*`) are replaced by generated ones. Clients
  who cast raw messages to those classes need to adjust. We think that is nobody.
  The raw listener keeps giving the raw bytes.

A CI job compares the new jar with 0.0.56 and fails on any source-incompatible change
to the public packages. The old `examples` module compiles against the new jar as a
second check.

---

## 4. Architecture

Four layers. Each one only talks to the one below.

```
 public API      OddsFeed, sessions, managers, entity interfaces
 core            entity façades, caches, recovery, producers, replay
 wire            feed decoder, REST client, generated XML models
 transport       AMQP connection, HTTP client
```

### Threading

- The AMQP consumer thread does one thing: decode the message, stamp it, put it on the
  session queue, ack. Nothing else.
- Each session has its own dispatcher. Client callbacks run there, in order per
  session. A slow callback slows its session. It cannot stall heartbeats.
- Blocking REST work runs on virtual threads. Parallel where independent, for example
  all competitors of a match at once.
- One `ScheduledExecutorService` for timers. No task ever blocks on it.

### Caches

- Caffeine, bounded by size and by time. Nothing is unbounded. No soft references.
- One documented rule: **no I/O while holding a lock.** Check the cache, release, fetch,
  merge. Concurrent misses for the same key wait for one fetch (single-flight).
- An API response is written into the cache it was fetched for. It does not trigger
  fetches into other caches on the same thread. Related data is loaded on demand,
  or asynchronously with a bounded queue.
- Fields have an owner. Feed messages own live status and scores. REST owns fixture
  data and the winner. A REST snapshot never overwrites a newer feed value, and a
  missing field in a message means "keep what you have", never "reset to zero".
- Loaded-locale marks expire. New sports, tournaments and markets show up without a
  restart.
- Every cache has a public clear method.

### Recovery and producers

- Recovery is a state machine per producer, with tests that drive it through every
  transition.
- Request ids are monotonic per process, never random.
- Recovery that times out is re-issued. Alive gaps and processing delays are detected
  and recovered from without a restart.
- Unknown producer ids are an error, not a fabricated producer.

### Connection

- Connection events: connecting, up, down, recovering. No duplicate "down" on a normal
  close.
- Reconnect with backoff. Exclusive queues are always re-declared.
- Broker limits (connections, queues) surface as clear errors, never as a silent hang.

### Watchdog

The SDK checks itself. If the delivery thread has not moved for a configured time, or
`ThreadMXBean` reports a monitor deadlock, it logs loudly. A stall must never be silent
again.

---

## 5. New in 1.0

Additive only. All of it exists in the Go SDK already.

- Entities: `Category` on tournaments, reference ids on matches and tournaments,
  `Statistics` on match status, `IconPath` and `Abbreviation`, competitor ids on
  tournaments, tournament ids on sports.
- Getters: single `Sport`, `Tournament`, `Player` by id, `ProducersInScope`,
  `ProducerStatus`, replay status.
- Cache control: clear methods per entity type, reload of void reasons.
- Configuration: default locale, preload locales, HTTP timeout, prefetch, max
  inactivity, max recovery time, exchange names, shutdown timeout, API call logging.
- Events: connection state changes, API call events with method, URL, status and
  latency, recovery handle with status and result.
- Telemetry: SDK version in the HTTP `User-Agent` and in AMQP client properties.

Things the Java SDK has and Go does not stay: multi-session with priority interests,
`setSpecificEventsOnly`, raw API data callback.

---

## 6. Two release lines

| Line | Branch | Stack | Versions | Until |
|---|---|---|---|---|
| Old | `release/0.x` | Kotlin, Gradle, Java 8 | 0.0.57+ | 31 March 2027, critical fixes only |
| New | `next`, then `main` | Java 25, Maven | 1.0.0-rc.N, then 1.0.0 | ongoing |

Every wire change, for example a new XML attribute, lands in both lines. The PR
template has a checkbox for it.

Timeline we communicated: test builds in October and November 2026, release at the
end of November or beginning of December 2026.

---

## 7. Tests

Three layers. No ticket is done without its tests.

1. **System tests, written first.** Black-box, public API only. A fake feed (in-process
   AMQP or a broker in a container) and a fake REST server serving the schema fixtures.
   They assert what a client can observe: which callbacks fire, entity values, locale
   handling, invalidation on fixture change, producer down and recovery, reconnect,
   replay, exception strategy. They run against 0.0.56 first, so we know each test
   actually tests something. Then they run against 1.0.0. Same tests, one version
   property.
2. **Unit and concurrency tests with every ticket.** Golden decode tests for every
   message and endpoint from the schema fixtures. Cache tests: expiry, eviction,
   locale fill-in, clear, single-flight. Deadlock tests: concurrent cold loads of every
   cache pair with latches, asserting no deadlocked threads and completion in time.
   Recovery state machine tests. Lifecycle races: open, close, reconnect.
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
- Cold path: parallel bounded fan-out for competitors and players, single-flight.
- Outage: serve stale data while a refresh is failing, back off per locale. Never
  collapse to one message per HTTP timeout.
- Measure JAXB unmarshal cost early. If it is too slow, the generated classes stay
  and a StAX reader replaces the unmarshaller.

---

## 9. Build and release

- Root `pom.xml`, modules `odds-feed` (published), `examples` (compiles against it,
  not published), `system-tests`. Maven wrapper checked in.
- JDK 25 toolchain, JaCoCo, Surefire and Failsafe, Enforcer, the API compatibility
  check, XML generation from the schema artifact.
- Version from the git tag. GitHub Actions on `v*` tags: build, compatibility check,
  sign, publish to Maven Central, create the GitHub Release with the jar and POM
  attached.
- Before any release, check what is already published. The registry, not git tags,
  is the source of truth for existing versions. Never two release tags on one commit.
- The old line keeps publishing to GitHub Packages from `release/0.x`.

---

## 10. Work breakdown

Small tickets, one PR each, one to three days. Each has a visible result. Order
matters where it says so, the rest can run in parallel.

### Phase 0 – Foundation

1. This design doc approved.
2. `next` branch, root POM, Maven wrapper, JDK 25, empty CI green. Only the
   `system-tests` module exists.
3. Fake REST server for tests, serving the schema fixtures.
4. Fake AMQP feed for tests, replaying fixture messages.
5. First system tests green against 0.0.56: open, receive an odds change, read a
   match, close.
6. Cut `release/0.x`, keep its Java 8 CI green, add the dual-line checkbox to the PR
   template.

### Phase 1 – Contract and wire

7. System tests: every feed message type and the callbacks it triggers.
8. System tests: locales, invalidation, producer down and recovery, reconnect,
   replay, exception strategy. Known differences from 0.0.x are listed, not fixed.
9. `odds-feed` module with the whole public API as source-compatible declarations
   and no behaviour. Old `examples` compile. Compatibility check runs in CI.
10. Generated feed models plus golden decode tests. One ticket per message family.
11. Generated REST models plus golden tests. One ticket per endpoint family.
12. HTTP client: all endpoints, retry, timeouts, error mapping, API call events.
13. Benchmark harness: corpus, JMH skeleton, CI budget check.

### Phase 2 – Core

Each ticket includes its concurrency and deadlock tests. System tests turn green
group by group.

14. Cache infrastructure: bounded, single-flight, per-locale fill-in, clear,
    field ownership.
15. Entity caches: match and fixture.
16. Entity caches: competitor, player, tournament, sport.
17. Catalog caches: market descriptions, void reasons, match status descriptions.
18. Entity façades and factories with parallel multi-locale loading.
19. AMQP layer: connection, reconnect, connection events, manual ack, prefetch,
    routing keys.
20. Message factory, markets and outcomes, session dispatch.
21. Producer manager and whoami.
22. Recovery state machine, including re-issue on timeout.
23. Replay manager.
24. `OddsFeed` façade, sessions, builder, idempotent lifecycle, watchdog.

### Phase 3 – Parity and polish

25. Field parity with the Go SDK, in small groups.
26. Option and method parity.
27. Telemetry headers and client properties.
28. Logging cleanup. Noisy logs are a client complaint.
29. README, examples, integration guide, FAQ update.
30. Sweep the Go and .NET SDK history since this document for fixes to port.

### Phase 4 – Release

31. Maven Central pipeline: namespace, signing, tag-driven publish.
32. First release candidate, soak on the test environment, candidates to clients.
33. Fix round.
34. End-of-life notice for 0.0.x sent to all clients, 1.0.0 released.

Critical path: 3 to 5, then 9, then 14, then 15 to 18, then 24, then 32. The
benchmark, the Central pipeline and the `release/0.x` cut fit into gaps.

---

## 11. Risks

- **Timeline.** Two weeks went to the hotfix already. If the schedule slips, the
  release candidate goes out later, not with fewer tests.
- **Silent behaviour differences.** Clients depend on things we do not know about.
  The system tests against 0.0.56 are our best defence. Release candidates to clients
  are the second.
- **JAXB speed.** Measured in Phase 1. Fallback is a StAX reader.
- **Two Java 8 clients.** They cannot use 1.0. The old line covers them until the
  end date. Anything beyond that is a business decision, not a technical one.
- **Two lines to maintain.** Every wire change is done twice until the old line ends.

---

## 12. Open questions

1. Do the generated XML classes stay public, or do we only promise the raw bytes on
   the raw listener? Proposal: internal.
2. JAXB or StAX for decoding? Decide with numbers from ticket 13.
3. Which clients test the release candidates? Needs an answer from customer success.
4. Do the priority-split session interests keep exactly today's semantics? Proposal:
   yes, they are public API.
5. Does the feed define `bet_stop` at all? If not, the type stays as legacy and is
   documented as never sent.
