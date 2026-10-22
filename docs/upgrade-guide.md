# Upgrading from 0.0.x to 1.0

## Before you start

- 1.0 needs **Java 25**. A client on Java 8 stays on 0.0.x, which gets critical fixes and additive wire fields until 31 March 2027. On 0.0.x, use 0.0.60 or later: 0.0.59 checks the broker's certificate and host name, parses XML with DTDs and external entities off and keeps the access token out of the SDK's logs, and 0.0.60 no longer sends the access token with your application's other HTTP requests.
- 1.0 is **source compatible** with 0.0.x and not binary compatible. Recompile everything that uses the SDK, including your own libraries. A library of yours that was compiled against 0.0.x and is used with 1.0 can fail with `NoSuchMethodError`.
- Packages and class names are unchanged: no import changes.

## 1. Change the dependency

0.0.x was published to GitHub Packages as `com.oddin.oddsfeed:odds-feed`. 1.0 is published to Maven Central as `gg.oddin.oddsfeed:odds-feed`. The group id changed because Maven Central verifies it against a domain we own. You change one line and can drop the registry and token setup.

Maven:

```xml
<!-- before -->
<dependency>
  <groupId>com.oddin.oddsfeed</groupId>
  <artifactId>odds-feed</artifactId>
  <version>0.0.60</version>
</dependency>

<!-- after -->
<dependency>
  <groupId>gg.oddin.oddsfeed</groupId>
  <artifactId>odds-feed</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle:

```kotlin
// before
implementation("com.oddin.oddsfeed:odds-feed:0.0.60")
// after
implementation("gg.oddin.oddsfeed:odds-feed:1.0.0")
```

Then remove what only 0.0.x needed: the GitHub Packages repository, the `oddin-github` server in `~/.m2/settings.xml` (or the repository block in your build script) and the token that went with them. Use whichever 1.0 version is the newest; release candidates are `1.0.0-rc.N`. The jar and the POM on Maven Central are signed (the `.asc` files next to them), and so are the files attached to the GitHub release.

## 2. Ban the old coordinates

Both jars contain the same packages and class names. If one dependency of yours still brings `com.oddin.oddsfeed:odds-feed` in, the class path holds two SDKs and which class wins depends on the order. Make the build refuse it. Add these once every module of yours has moved.

Maven, with the Enforcer plugin (the rule checks transitive dependencies too):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.6.3</version>
  <executions>
    <execution>
      <id>no-legacy-oddsfeed</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <excludes>
              <exclude>com.oddin.oddsfeed:odds-feed</exclude>
            </excludes>
            <message>com.oddin.oddsfeed:odds-feed is the 0.0.x SDK. Use gg.oddin.oddsfeed:odds-feed (1.0).</message>
            <searchTransitive>true</searchTransitive>
          </bannedDependencies>
        </rules>
        <fail>true</fail>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Gradle (Kotlin DSL), failing the resolution of any configuration that asks for it:

```kotlin
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.oddin.oddsfeed" && requested.name == "odds-feed") {
            throw GradleException(
                "com.oddin.oddsfeed:odds-feed is the 0.0.x SDK. Use gg.oddin.oddsfeed:odds-feed (1.0).")
        }
    }
}
```

In Groovy: `configurations.configureEach { resolutionStrategy.eachDependency { if (it.requested.group == 'com.oddin.oddsfeed' && it.requested.name == 'odds-feed') { throw new GradleException('...') } } }`.

## 3. A last 0.0.x release will point at 1.0

When 0.0.x support ends (31 March 2027) we publish one last 0.0.x version whose POM does nothing but relocate to `gg.oddin.oddsfeed:odds-feed` at a fixed 1.x version. A Maven build still on the old coordinates then prints a warning that the artifact has moved. **Gradle follows a relocation without showing its message**, so a Gradle build moves to the new coordinates silently, and onto Java 25. Do not wait for the warning: move now.

If you are a Java 8 client, pin the last 0.0.x version before that release comes out. A build on `0.0.+`, or one that Renovate or Dependabot bumps as a patch, would take the relocation and get the Java 25 jar. We will send a reminder shortly before.

## 4. Recompile and fix

Differences that stop compilation or change a type, from [`odds-feed/src/test/resources/api/differences.txt`](../odds-feed/src/test/resources/api/differences.txt) (the list is complete, against 0.0.60; a test keeps it so):

| What | What to do |
| --- | --- |
| `MarketMessage.getMarkets()` is `List<? extends Market>` | Where you assign it to `List<Market>`, add one cast, or use `List<? extends Market>`. |
| Kotlin `copy()`, `componentN()`, named arguments | Do not use them on the SDK's classes; pass arguments by position. |
| `*Impl` classes, caches, `ApiClient`, `di` and the other internal types | Stop importing them. Use the public interfaces. |
| `javax.xml.bind` annotations on the XML classes are `jakarta.xml.bind` | A `JAXBContext` of your own over the `OF*` or `RA*` classes must use Jakarta. |
| Some `OF*` and `RA*` getters are gone or reshaped | See the release notes' list; they are things the feed or API never sends, or types the schema merged. |
| Deprecated getters (`getRefId`, `getBetStopReason`, `FixtureChange.getStartTime`, ...) | They stay and return null; stop using them. |
| `build()` and `buildReplay()` after `open()` | Build every session before `open()`. |

## 5. Check the behaviour

The list of behaviour changes is [`system-tests/KNOWN-DIFFERENCES.md`](../system-tests/KNOWN-DIFFERENCES.md) (KD-1 to KD-44; KD-8 and KD-10 were withdrawn), summarized in each release's notes and, one line each, in the table below. The ones most clients meet:

- **Start with the API down (KD-36).** 0.0.x's start fetched whoami once and failed within seconds when the API was down. 1.0's start fetches whoami and the producer list, and tries again, a second apart, until both answered or the startup deadline passed: three HTTP timeouts, **90 seconds by default**. A first manager call or a readiness check that failed fast now waits up to 90 s, and so does each retry, because a failed start keeps nothing. If you want a quick failure, set `setStartupTimeout(Duration.ofSeconds(10))`; it also rides out only an outage that short. A feed you give up on must be closed: its events thread and watchdog stay until `close()`.
- **`open()` is one-shot (KD-34).** After a failure, `close()` and build a new `OddsFeed`.
- **Threads (KD-41, KD-35).** Message callbacks run on `oddsfeed-session-<id>`, not on the AMQP client's thread; global callbacks run in order on one events thread, and behind a slow callback a producer's status or the connection state is replaced by its newest.
- **The recovery timestamp (KD-28, KD-32).** Set it before `open()`; read it as an `Instant` from `getTimestampForRecovery()` and catch `IllegalArgumentException` on the way back. See the [integration guide](integration-guide.md#resuming-after-a-restart).
- **TLS (KD-19).** The broker's certificate and host name are checked, against the JVM's trust. 0.0.59 checks them too; 0.0.58 and older trusted any certificate, so a client that connected only because of that now fails at `open()`. If you have a truststore of your own or a proxy that inspects TLS, give it with `setMessagingSslContext` (0.0.59 has no such setting: the proxy's CA goes into the JVM's trust).
- **Unparsable messages (KD-9, KD-41).** A callback that throws and an SDK failure after decoding go to `onCallbackFailure`, not `onUnparsableMessage`; bodies over 1 MiB are unparsable.
- **Fixture changes** are delivered once per feed within an hour, as in 0.0.x; a replay run again within the hour has them dropped.
- **Recovery** is stricter and visible: the stale-message safety net, backoff, a status per event recovery. Producers can come up later than in 0.0.x (KD-37).
- **A negative initial snapshot interval (KD-44).** `setInitialSnapshotRecoveryInterval` throws `IllegalArgumentException` for a negative `Duration` and keeps its old value; 0.0.x took it and asked from a time in the future. If you compute the interval, check it is not negative. Zero is as before.
- **A step of the wall clock (KD-43).** A host clock corrected by more than the maximum inactivity, or a virtual machine that resumes, took every producer down in 0.0.x. In 1.0 it takes none down. Do not rely on either.

### Every behaviour difference, one line each

| KD | What changes | What to do |
| --- | --- | --- |
| 1 | `onEventRecoveryCompleted` is called (0.0.x never called it). | Use it. |
| 2 | As 0.0.x: an alive saying a down producer is unsubscribed raises no `onProducerStatusChange`. | `onProducerCauseChange` hears it. |
| 3 | A message from a producer the list does not have is dropped, and `getProducer` returns null. | Handle null. |
| 4 | `FixtureChange.getStartTime()` returns null (it returned 1 January 1970). | Stop using it. |
| 5 | A cancelled market's `getVoidReason()` returns the reason (it was always null). | Nothing. |
| 6 | Bet stop groups separated by a pipe are split. | Match them one by one. |
| 7 | The alive check runs from the start, every second; a silent producer, one never up included, is down after 20 s. | Expect the status sooner (0.0.x waited a minute). |
| 9 | A callback that throws goes to `onCallbackFailure`, not `onUnparsableMessage`. | Count your failures there. |
| 11 | A recovery request the API refused is asked for again with backoff, three times, then after a cool-down. | Nothing. |
| 12 | After a reconnect the producer goes down and a recovery covers the gap. | Expect a down and an up. |
| 13 | An older message does not overwrite the status of a newer one; both are delivered. | Nothing. |
| 14 | A message older than 20 minutes does not write the match status as current. | Nothing. |
| 15 | Closing a feed whose start failed logs nothing. | Nothing. |
| 16 | A login the broker refuses surfaces as `InitException` (the cause is a copy of the AMQP client's exception). | Catch `InitException`. |
| 17 | The stale-message safety net is on by default. | Tune `setStaleMessageLimit` and `setStaleMessageWindow`, or listen to `onSafetyNetEvent`. |
| 18 | A producer listed as live and prematch (`live\|prematch`) has both scopes. | Nothing. |
| 19 | The broker's certificate and host name are checked (0.0.59 too). | `setMessagingSslContext` for your own truststore. |
| 20 | Home and away are the competitors qualified `home` and `away`; a race has neither (null). | Read null. |
| 21 | The feed's `winner_id` is used. | Nothing. |
| 22 | A getter of several locales fails as a whole when one fails. | Handle the failure. |
| 23 | Closing the feed is not reported as `onConnectionDown`. | Nothing. |
| 24 | American odds are the moneyline odds; odds of 1 or less read null. | Read null. |
| 25 | A lost `snapshot_complete` is given up after five minutes and asked for again with backoff. | Nothing. |
| 26 | One recovery in flight per producer; an unsubscribed alive counts as an alive. | Nothing. |
| 27 | A channel the broker closes on a live connection is opened again and the gap recovered. | Nothing. |
| 28 | `getTimestampForRecovery()` is the resume point the sessions processed, not the last alive. | Set it before `open()` and catch `IllegalArgumentException`. |
| 29 | `build()` and `buildReplay()` after `open()` throw `IllegalStateException`. | Build before `open()`. |
| 30 | An event recovery asked for before `open()` returns null; after `open()` it is refused while the connection is down or with 128 in flight. | Handle null. |
| 31 | Raw API data reaches the extended listener before `open()` too, on the events thread, and can be dropped past 1 000 entries or 32 MiB. | Read it on the events thread. |
| 32 | A recovery-from timestamp set before `open()` is kept; one set after is ignored with a warning. | Set it before `open()`. |
| 33 | A recovery after a producer gap starts from the newest subscribed alive, capped at the producer's now. | Nothing. |
| 34 | `open()` is one-shot. | `close()` and build a new `OddsFeed`. |
| 35 | Global callbacks run in order on one thread; a queued status gives way to the newest. | Do not count every step. |
| 36 | A start needs the API and waits up to 90 s (`setStartupTimeout`). | Set a shorter timeout if you want a quick failure. |
| 37 | A recovery completes when every session that takes the producer has seen its `snapshot_complete`. | Nothing. |
| 38 | The processing delay follows your sessions only. | Nothing. |
| 39 | A field a message leaves out keeps its value. | Nothing. |
| 40 | A tournament with no competitors has an empty list. | Nothing. |
| 41 | Sixteen small changes: callback thread, REST deadline and limits, message size, interest check order, cache ages and sizes of lists, locale handling and others. | See `KNOWN-DIFFERENCES.md`, KD-41. |
| 42 | A message stamped 0 or less does not move the last processed timestamp. | Nothing. |
| 43 | A step of the wall clock does not move the recovery's deadlines. | Nothing. |
| 44 | A negative initial snapshot interval is refused. | Do not pass one. |

Then use the new things: node ids and the other [onboarding checklist](integration-guide.md#onboarding-checklist) items, `getHealth()` (it has a part for the API, which reads `STALLED` when the API refuses the access token), `onHealthEvent`, the eager preload, `Tournament.getIconPath()`, `Tournament.getCompetitorIds()` and `Sport.getTournamentIds()`, and the [examples](../examples/odds-feed-1.0).

## Help

Differences you did not expect are bugs in this list or in the SDK: tell us.
