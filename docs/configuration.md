# Configuration

Everything is set on the builder, `OddsFeed.getOddsFeedConfigurationBuilder()`, and checked when the
setter is called or, for the rules that span two options, in `build()`. A null argument throws
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
| `setSDKNodeId(int)` | none | Distinct per running instance ([checklist](integration-guide.md#onboarding-checklist)). Without one, the instance also takes the snapshot completes other instances asked for, and drops them ([FAQ](faq.md#the-log-says-a-snapshot-complete-is-for-a-request-this-feed-did-not-ask-for)). |
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
| `setMessagingSslContext(SSLContext)` | the JVM's default trust | The context the broker's certificate is checked with. The certificate and the host name are always checked; 0.0.x trusted any certificate up to 0.0.58. For your own truststore or a proxy that inspects TLS. |
| `setMaxInactivitySeconds(int)` | 20 | More than 10, the seconds between a producer's alives. How long a producer may be silent, or a session may process its messages late, before it counts as down. |
| `setMaxRecoveryExecutionMinutes(int)` | 360 | At least 360. Bounds a recovery that keeps coming and event recoveries. A recovery whose `snapshot_complete` is lost is given up after 5 minutes regardless. |
| `setShutdownTimeout(Duration)` | 5 s | Positive, at most 1 hour. How long `close()` waits for all the feed's threads together. |
| `setStaleMessageLimit(Duration)` | 2 min | More than 10 s, at most 1 day (a day is as good as off). How old, by the producer's clock, live messages may be before the safety net counts a session behind. |
| `setStaleMessageWindow(Duration)` | 1 min | At least 1 s, at most 1 day. How long they must stay that old before the safety net acts. |
| `setExchangeName(String)` | `oddinfeed` | Not blank, at most 255 bytes in UTF-8. |
| `setReplayExchangeName(String)` | `oddinreplay` | Not blank, at most 255 bytes in UTF-8. |
| `setFutureStampMargin(Duration)` | 30 s | Positive, at most 1 day (a day is as good as off). How far ahead of its producer a message's timestamp may be and still hold back the status of the messages that follow it. See [the status order](#the-order-of-the-live-status) below. |
| `setTimelyMessageAge(Duration)` | 10 s, or half the margin if that is shorter | Positive, at most half the margin (checked in `build()`). How old, by the producer's alives, a message may be to write the status in place of one with a later timestamp. |
| `setApiCallLogging(boolean)` | off | Each attempt of an API call at INFO, under the logger `com.oddin.oddsfeedsdk.api-calls`; a complete record, see [the API call log](health-and-logging.md#the-api-call-log). `onApiCall` hears them either way, best effort. |
| `setDefaultLocale(Locale)` | English | Must name a language (not `Locale.ROOT`). The locale a getter without one reads in, and the one messages are built in. |
| `setPreloadLocales(List<Locale>)` | none | Each must name a language. See below. |
| `setEagerEntityPreload(boolean)` | off | See below. |

The health thresholds, `setCatalogStaleLimit`, `setCallbackStallLimit`, `setQueueStallLimit`,
`setWatchdogInterval` and `setConnectionDownLimit`, are on [the health page](health-and-logging.md#thresholds).

The safety net's other numbers are fixed: three resets per session per ten minutes, a minute's
backoff doubling between them.

### The order of the live status

A match's live status and scores are written from a message only if no later timestamp of its
producer has written them already, so a delayed message does not turn a score back (still delivered
either way). A timestamp that is wrong and ahead of its producer - a component with a fast clock -
would then block every correct message after it until the real time catches up. To avoid that, a
message older than the one that wrote last writes anyway, and its timestamp becomes the new
watermark, when all of these hold:

- it was received after every message that wrote the status before it, by the SDK's own clock;
- the time since the latest receipt of a message that wrote the status, plus the gap between
  their timestamps, is more than the **future stamp margin** (30 s): two ordinary deliveries do not
  differ so much;
- it is timely: its age by its producer's alives is at most the **timely message age** (10 s), so a
  message that sat in a backlog never counts;
- it is not older than the update the status had before a timestamp ahead of its producer wrote
  it: a message stamped ahead of the producer's now by more than the timely age, however little it
  raises the status's timestamp, leaves the timestamp it raised as the floor, so an ordinary delayed
  message cannot put an older status back. A message at the producer's now (its age within the timely
  age, on either side) that raises the status's timestamp forgets the floor: it is then as stale as
  the quiet since;
- it is not an answer to a recovery request: a recovery never moves the live state back, and the
  next live message repairs a bad timestamp.

A timestamp ahead by less than the margin cannot be told from a delivery delay: it holds the
messages that follow back until their timestamps catch up, at most the margin. Of two timestamps
ahead, the one received last writes when the same rule holds for it; with the stamps closer than the
margin the first stays in charge until they catch up. A margin of a few seconds lets the ordinary jitter of the
deliveries pass for a bad timestamp and writes an older status over a newer one; the margin less the
timely age is how late a producer's alives may be before a delayed message passes for a timely one,
and the timely age is at most half the margin so that a timely message is never taken for a delayed
one against a status written at its producer's now.

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
