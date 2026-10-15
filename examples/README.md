# Examples

Two sets, one per line of the SDK:

- [`odds-feed-1.0`](odds-feed-1.0): the examples of 1.0 (Java 25, Maven). Start here.
- This directory's Gradle project (`src`): the examples of 0.0.x (Java 8, `com.oddin.oddsfeed:odds-feed`).
  They stay as they are on purpose: the build compiles them against 1.0 to check that code written
  for 0.0.x still compiles.

## The 1.0 examples

Each is one short class with a `main`:

| Class | Shows |
|---|---|
| `Quickstart` | the environment and token, a session with a listener, `open()`, `getHealth()`, and `close()` from a shutdown hook and a `finally` |
| `ResumeAfterRestart` | saving each producer's `getTimestampForRecovery()` and passing it back with `setProducerRecoveryFromTimestamp`, 0 when it is too old |
| `EventRecovery` | `initiateEventOddsMessagesRecovery`, `onEventRecoveryCompleted` and `getEventRecoveryStatus` |
| `Replay` | a replay session: `buildReplay()`, a list of events, `play(...)` |

`LoggingGlobalListener` (the feed's events, with the health events, the connection state and the
fatal error) and `LoggingListener` (a session's messages) are shared by all four; `ExampleConfig`
reads the configuration from the environment.

### Environment

| Variable | |
|---|---|
| `ODDIN_GG_ODDS_FEED_TOKEN` | your access token; required. It is never written into code. |
| `ODDIN_GG_ODDS_FEED_ENVIRONMENT` | `integration` (default), `test` or `production` |
| `ODDIN_GG_ODDS_FEED_NODE_ID` | the node id, 1 by default; give every instance that runs at the same time its own |

Your IP address has to be allowed by Oddin before the API and the broker accept the token.

### Build and run against the SDK in this repository

From the repository root, with JDK 25. This builds the SDK and compiles the examples with it,
needs no token and no registry, and installs nothing:

```
./mvnw package -DskipTests -pl examples/odds-feed-1.0 -am
```

The same module is part of the default build (`./mvnw verify`), so a change to the SDK that
breaks an example breaks the build. The build copies the SDK's jar and what it needs into
`examples/odds-feed-1.0/target/lib`; run an example from there:

```
export ODDIN_GG_ODDS_FEED_TOKEN=...      # your token
java -cp 'examples/odds-feed-1.0/target/classes:examples/odds-feed-1.0/target/lib/*' \
    gg.oddin.oddsfeed.examples.Quickstart
```

`ResumeAfterRestart` takes the state file as its argument, `EventRecovery` a producer id and an
event URN, `Replay` the events to replay.

### Use the 1.0 SDK in your own project

1.0 is on Maven Central; no registry and no token are needed to fetch it:

```xml
<dependency>
  <groupId>gg.oddin.oddsfeed</groupId>
  <artifactId>odds-feed</artifactId>
  <version>1.0.0-rc.1</version>
</dependency>
```

```kotlin
implementation("gg.oddin.oddsfeed:odds-feed:1.0.0-rc.1")
```

Use the newest 1.0 version. Add an SLF4J backend, as `odds-feed-1.0/pom.xml` adds Logback: the
SDK logs through SLF4J and brings none. The packages are those of 0.0.x, so imports do not change.

To try an SDK you built yourself in a project of your own, run `./mvnw install -DskipTests -pl
odds-feed -am` in this repository (it puts `1.0.0-SNAPSHOT` into your local Maven repository) and
depend on `gg.oddin.oddsfeed:odds-feed:1.0.0-SNAPSHOT` with `mavenLocal()` (Gradle) or as it is
(Maven).

More: the [integration guide](../docs/integration-guide.md), [configuration](../docs/configuration.md),
[health and logging](../docs/health-and-logging.md) and the [FAQ](../docs/faq.md).

## The 0.0.x examples

Java 8, Gradle, `com.oddin.oddsfeed:odds-feed` from the GitHub packages registry or your local Maven repository.

- `../library/gradlew clean publishToMavenLocal -p ../library`
- `./gradlew clean build`
- write your token into the environment property `ODDIN_GG_ODDS_FEED_TOKEN`
- `./gradlew run`
