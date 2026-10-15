# Configuration

Everything is set on the builder, `OddsFeed.getOddsFeedConfigurationBuilder()`, and checked when the
setter is called or, for the one rule that spans two options, in `build()`. A null argument throws
`NullPointerException` at once. An out-of-bounds value throws `IllegalArgumentException` with the
bounds in its message. [Integration guide](integration-guide.md) - [Health and logging](health-and-logging.md)

## Required

| Option | Meaning |
|---|---|
| `selectProduction()`, `selectIntegration()`, `selectTest()`, each with an optional `Region` (`DEFAULT`, `AP_SOUTHEAST_1`) | The environment: broker and API hosts. |
| `selectEnvironment(messagingHost, apiHost[, port])` | The same with hosts you name; the broker port is 5672 unless given. |
| `setAccessToken(String)` | Your token. It is the API credential and the broker login (see [Logging](health-and-logging.md#logging)). |

`build()` throws `IllegalArgumentException` without an environment or a token.

## Options that exist in 0.0.x

| Option | Default | Notes |
|---|---|---|
| `setSDKNodeId(int)` | none | Distinct per running instance ([checklist](integration-guide.md#onboarding-checklist)). |
| `setExceptionHandlingStrategy(ExceptionHandlingStrategy)` | `THROW` | `CATCH` makes getters log and return null; see [the FAQ](faq.md#what-do-the-getters-do-when-the-api-fails). |
| `setInitialSnapshotRecoveryInterval(Duration)` | none: a full snapshot | How far back a start with no resume point asks for. Zero asks from now, with no history; a negative value throws `IllegalArgumentException` (0.0.x accepted it). |
| `setMaxMatchCacheSize(long)` | 10 000 | Entries; the least recently used goes first and is fetched again when read. A value under 1 counts as 1. |
| `setMaxFixtureCacheSize(long)` | 10 000 | As above. |
| `setMaxCompetitorCacheSize(long)` | 20 000 | As above. |
| `setMaxPlayerCacheSize(long)` | 50 000 | As above. |

The entity caches also expire by age: matches, fixtures and tournaments after 12 hours,
competitors and players after 24 hours. The live state of a match (status, scores) is fresh for 20
minutes after the feed or the API last wrote it.

## New in 1.0

| Option | Default | Bounds, and what it does |
|---|---|---|
| `setHttpClientTimeout(Duration)` | 30 s | Positive. The deadline of one API call, covering the wait for a permit, every attempt and every pause between them. A getter can wait longer: it loads twice at most, and a fan-out waits about twice the timeout. |
| `setRestConcurrencyLimit(int)` | 16 | At least 1. Entity requests in flight at once. Half of it, 1 to 16, goes to the background loaders (below). |
| `setStartupTimeout(Duration)` | 3 x HTTP timeout, 90 s | Positive. How long the feed's start keeps trying the API before it throws `InitException`. |
| `setAmqpPrefetch(int)` | 200 | 1 to 10 000; 0 is refused because the broker reads it as unlimited. Unacknowledged messages the broker hands each session. |
| `setMaxMessageSize(int)` | 1 MiB (1 048 576 bytes) | At least 1. The largest body decoded. A larger one is not decoded; it reaches `onUnparsableMessage` and `onCallbackFailure`. |
| `setMessagingSslContext(SSLContext)` | the JVM's default trust | The context the broker's certificate is checked with. The certificate and the host name are always checked; 0.0.x trusted any certificate. For your own truststore or a proxy that inspects TLS. |
| `setMaxInactivitySeconds(int)` | 20 | More than 10, the seconds between a producer's alives. How long a producer may be silent, or a session may process its messages late, before it counts as down. |
| `setMaxRecoveryExecutionMinutes(int)` | 360 | At least 360. Bounds a recovery that keeps coming and event recoveries. A recovery whose `snapshot_complete` is lost is given up after 5 minutes regardless. |
| `setShutdownTimeout(Duration)` | 5 s | Positive, at most 1 hour. How long `close()` waits for all the feed's threads together. |
| `setStaleMessageLimit(Duration)` | 2 min | More than 10 s, at most 1 day (a day is as good as off). How old, by the producer's clock, live messages may be before the safety net counts a session behind. |
| `setStaleMessageWindow(Duration)` | 1 min | At least 1 s, at most 1 day. How long they must stay that old before the safety net acts. |
| `setExchangeName(String)` | `oddinfeed` | Not blank, at most 255 bytes in UTF-8. |
| `setReplayExchangeName(String)` | `oddinreplay` | Not blank, at most 255 bytes in UTF-8. |
| `setApiCallLogging(boolean)` | off | Each attempt of an API call at INFO. `onApiCall` hears them either way. |
| `setDefaultLocale(Locale)` | English | Must name a language (not `Locale.ROOT`). The locale a getter without one reads in, and the one messages are built in. |
| `setPreloadLocales(List<Locale>)` | none | Each must name a language. See below. |
| `setEagerEntityPreload(boolean)` | off | See below. |

The health thresholds, `setCatalogStaleLimit`, `setCallbackStallLimit`, `setQueueStallLimit`,
`setWatchdogInterval` and `setConnectionDownLimit`, are on [the health page](health-and-logging.md#thresholds).

The safety net's other numbers are fixed: three resets per session per ten minutes, a minute's
backoff doubling between them.

### Recovery behaviour you do not configure

A producer's recovery waits 5 minutes at most for its `snapshot_complete`. One the API does not
accept, or that times out, is asked for again after 5 seconds, doubling, up to three times in a row;
the producer then stays down with `RECOVERY_FAILED`, and the count starts again after 10 minutes or
at once when an alive arrives after a gap.

## Preload locales and the eager preload

Both are about moving API calls off your callbacks and off the first read.

**Preload locales** (`setPreloadLocales`). With none, which is the default, nothing is loaded ahead:
every catalog loads on its first read, as in 0.0.x. With locales set, `open()` starts loading, in
the background and for each locale, the market descriptions, the match statuses and the sports,
and the void reasons once. Nothing waits for it: `open()` returns, the first messages may still
read a catalog that is loading, and a load that fails is logged as a warning and made again on
the first read. The default locale is not added for you: list it as well, if you want it
preloaded. A locale given twice counts once, and `en` and `en_US` are loaded separately, though
the API, asked by language, answers both alike.

**Eager entity preload** (`setEagerEntityPreload(true)`). Off by default, as 0.0.x loaded an entity
on its first read. On, the match a message's routing key names is queued for loading as the
message is handed to its session, in the default locale and the preload locales, so by the time
your callback reads the match it is loaded or loading, and the read joins the load.

- Nothing waits for a preload. A message is delivered whether its match has loaded or not.
- A fixture change preloads nothing: the session invalidates the match before the callback.
- A load of the same match and locale already queued or under way is not queued again. The queue
  holds 1 000 loads; one that finds no room is dropped and counted, and a load that fails is logged,
  the first and then every thousandth, and counted. Both counts are in `getHealth()`
  (`caches`).
- The loads run on the side-load workers, never on the broker client's thread or a session's.
  There are half as many workers as `setRestConcurrencyLimit` allows, at least 1 and at most 16:
  8 by default. A match's competitor and player loads run on the same workers. So after a restart,
  when every cache is cold, the burst of loads takes at most half the REST permits and works through
  at the pace of those workers, and your own reads keep the other half. To get through a restart's
  loads faster, raise the REST concurrency limit; a value of 1 leaves one worker, which can hold the
  only permit.
- The catalog preload above is separate: it runs on the SDK's own virtual threads, under its own
  permits, and does not use the side-load workers.

Without the eager preload, a getter in a callback on a cold entity calls the API on the session's
thread and holds that session, and only that session, for the call.
