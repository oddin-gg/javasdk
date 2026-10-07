# Known differences between 0.0.x and 1.0

The system tests run unchanged against the old 0.0.x SDK and the new 1.0 SDK. Where 0.0.x
behaves in a way 1.0 changes on purpose, or has a bug the 1.0 design fixes, the tests do not
assert the old behaviour as if it were the contract. They list it here and pin it with
`KnownDifference` (in `src/test/java/.../support`):

```java
KnownDifference.EVENT_RECOVERY_NOT_REPORTED.expect(
    () -> /* what 0.0.x does */,
    () -> /* what 1.0 does */);
```

Only the expectation for the SDK under test runs. The line comes from the `sdk.version` the
build resolved, checked against the jar the SDK was loaded from. The 0.0.x run proves the
scenario observes what it claims to; the 1.0 run checks the new behaviour. Where this list says
"To be decided", the scenario uses `expectLegacy` instead: it checks 0.0.x and, on 1.x, skips the
rest of the scenario with the entry's id as the reason, until someone decides and writes the 1.0
expectation.

The differences are listed, not fixed: 0.0.x stays as it is. They are behaviour the system tests
found; the API-level differences 1.0 accepts are in NEXT.md, section 3. `KnownDifferenceListTest`
keeps this file and the code in step: every `KnownDifference` has an entry here, every entry that
names a test has a `KnownDifference`, the named tests exist, and "To be decided" here matches the
code.

Each entry says what 0.0.x does, what 1.0 does and where NEXT.md says so, why it matters, which
test pins it, and whether it was found by a test against 0.0.56 or by reading the 0.0.56 source.

## KD-1 An event recovery is never reported complete

- **0.0.x:** `onEventRecoveryCompleted` is never called. The event recovery request goes out with
  the id `initiateEventOddsMessagesRecovery` returns, and the snapshot complete carrying that id
  arrives, but 0.0.x counts it only on sessions that are neither live-only nor prematch-only,
  while completing an event recovery needs a live-only or prematch-only snapshot complete for each
  of the producer's scopes.
- **1.0:** Called once the snapshot complete of the request has been seen. Recovery completion is
  one of the events-dispatcher callbacks (NEXT.md section 4, Threads), and snapshot completion is
  tracked per message interest (section 4, Recovery and producers).
- **Why:** A client that asks for an event recovery cannot tell when it is done.
- **Pinned by:** `ProducerStatusScenarioIT.theSnapshotCompleteOfAnEventRecoveryCompletesIt`
- **Found:** by test against 0.0.56, and in the source (`validateEventSnapshotComplete`).

## KD-2 A producer that is still down reports nothing when an alive says it is unsubscribed

- **0.0.x:** Every producer starts down. An alive with `subscribed="0"` for a producer that is
  down changes neither the down flag nor the reason, so `onProducerStatusChange` is not called.
  The SDK does ask for a recovery.
- **1.0:** As 0.0.x. Every producer starts down, and the status callback fires only when the
  down flag or the public reason changes, so it is not called here; the recovery is asked for.
  The cause changes, to unsubscribed, and `onProducerCauseChange`, which 1.0 adds, hears it
  with the cause `UNSUBSCRIBED` (NEXT.md section 4, Recovery and producers).
- **Why:** A client waiting for a first status event after `open()` sees none; the choice was
  made on purpose, to keep 0.0.x's callback as clients know it.
- **Pinned by:** `ProducerRecoveryScenarioIT.anUnsubscribedAliveForAProducerStillDownIsNotReported`
- **Found:** by test against 0.0.56.

## KD-3 A producer the producer list does not have is made up

- **0.0.x:** A message from such a producer is dropped with only a warning in the log, "Creating
  unknown producer: 7". `ProducerManager.getProducer(7)` returns a made-up producer named
  "unknown", described as "unknown producer" - and, by the source, enabled and in both scopes.
- **1.0:** "Unknown producer ids are an error, not a fabricated producer" (section 4, Recovery and
  producers). The message is still not delivered; `getProducer` for an unknown id returns null,
  as its signature allows (ticket 23). The other `ProducerManager` methods keep 0.0.x's answers for
  it: not enabled, down, and setters that change nothing.
- **Why:** A made-up producer hides a wrong id or a producer list the SDK did not load.
- **Pinned by:** `ProducerRecoveryScenarioIT.aMessageFromAnUnknownProducerIsNotDelivered`
- **Found:** by test against 0.0.56.

## KD-4 A fixture change starts at 1 January 1970

- **0.0.x:** `FixtureChange.getStartTime()` returns `new Date(0)`. The feed schema has no
  `start_time` on `fixture_change`, and 0.0.x reads the absent attribute as 0.
- **1.0:** Returns null and is deprecated: getters for attributes the feed never sends stay,
  deprecated, and return null (NEXT.md section 3, difference 3).
- **Why:** 1970 reads as a real date.
- **Pinned by:** `FeedMessageScenarioIT.aFixtureChangeReachesOnFixtureChange`
- **Found:** by test against 0.0.56.

## KD-5 A cancelled market's void reason is always null

- **0.0.x:** `MarketCancel.getVoidReason()` always returns null. It is deprecated in favour of
  `getVoidReasonId()` and `getVoidReasonParams()`, which work, although the market carries
  `void_reason` too - it is in the schema and in the bet cancel fixture.
- **1.0:** Returns the `void_reason` the market carries, null when it carries none (ticket 22).
  Difference 3 in NEXT.md section 3 covers attributes the feed never sends; `void_reason` is one
  it sends.
- **Why:** A client still on the deprecated getter gets nothing.
- **Pinned by:** `FeedMessageScenarioIT.aBetCancelReachesOnBetCancelWithItsWindowAndVoidReasons`
- **Found:** by test against 0.0.56, and in the source (`MarketCancelImpl`).

## KD-6 Bet stop groups separated by a pipe are not split

- **0.0.x:** Kotlin's `split("\\|")` takes its argument literally, so it splits on the two
  characters `\|`, not on `|`. A bet stop with `groups="winner|handicap"` has one group,
  `winner|handicap`. The same split leaves a producer in both scopes with none (KD-18).
- **1.0:** The groups are split on the pipe, as the schema documents them: `winner|handicap` is
  two groups, `winner` and `handicap`. The bet stop groups come with the message factory
  (ticket 22).
- **Why:** A client matching bet stop groups one by one never matches.
- **Pinned by:** `FeedMessageScenarioIT.aBetStopForSeveralGroupsNamesEach`
- **Found:** by test against 0.0.56.

## KD-7 The producer watchdog first runs a minute after open

- **0.0.x:** The recovery watchdog runs first 60 s after `open()`, then every 10 s. Its first run
  reports every producer without an alive in the last 20 s down with `ALIVE_INTERVAL_VIOLATION`,
  including producers that were never up.
- **1.0:** The first check is the maximum inactivity, 20 s by default, after `open()`, then every
  second: a producer without an alive for longer is reported down with `ALIVE_INTERVAL_VIOLATION`,
  one never up included. There is no minute of grace (NEXT.md section 4, Recovery and producers).
- **Why:** Any scenario longer than a minute sees these status changes on top of what it drives;
  `ReconnectScenarioIT` stays under the minute because of it. A client hears of a silent producer
  20 s after `open()` rather than 60 s.
- **Pinned by:** none; a minute-long scenario in every run is not worth it.
- **Found:** by reading the source (`RecoveryManagerImpl.open`, `timerTick`), and seen once in a
  scenario, not kept, that opened the feed and waited 75 s: both producers reported down with
  `ALIVE_INTERVAL_VIOLATION` 60 s after `open()`, neither of them ever up.

KD-8, a replay session next to a live one, was withdrawn: 0.0.x refuses that setup and 1.0 keeps
refusing it, so it is no difference (NEXT.md section 13, 2026-09-28).

## KD-9 A callback that throws is reported as an unparsable message

- **0.0.x:** Logs "Failed to process message" and hands the same message to
  `onUnparsableMessage`, as if the feed had sent something it could not read. The session goes
  on.
- **1.0:** The exception is caught, counted and reported through the listener-exception hook on
  the global listener, flagged as coming from client code; the unparsable callback is only for
  messages that did not decode (section 4, Delivery).
- **Why:** A client bug shows up as a feed problem.
- **Pinned by:** `ThrowingCallbackScenarioIT.aCallbackThatThrowsDoesNotStopTheSession`
- **Found:** by test against 0.0.56.

KD-10, a collection that cannot load being null under `CATCH` where 0.0.x had it empty, was
withdrawn: 1.0 keeps 0.0.x's answer. A match's competitors are none, and so are the lists 0.0.x
answered an outage with, under either strategy (NEXT.md section 3, Behaviour that stays).
`ExceptionStrategyScenarioIT.underCatchAGetterTheApiCannotServeReturnsNull` checks it on both lines.

## KD-11 A recovery request the API refused is not asked for again

- **0.0.x:** When the API refuses the recovery request, the recovery stays marked as started, and
  later alives ask for another only once the maximum recovery time, 360 minutes by default, has
  passed. The producer stays down meanwhile.
- **1.0:** Re-issued with backoff, at most three times in a row, re-armed after ten minutes or
  when an alive arrives after a gap (section 4, Recovery and producers). The test allows 30 s for
  the second request.
- **Why:** A short API outage at the wrong moment keeps a producer down for hours.
- **Pinned by:** `RestOutageScenarioIT.aRecoveryRequestTheApiRefusedIsAskedForAgain`
- **Found:** by test against 0.0.56.

## KD-12 A reconnect does not lead to a recovery

- **0.0.x:** After the AMQP client reconnects, a producer that was up stays up and its alives do
  not lead to a recovery, so what was sent while the connection was down is not recovered -
  unless the watchdog (KD-7) takes the producer down first.
- **1.0:** "Exclusive queues are always re-declared; whatever the broker buffered for the old
  queue is gone, and recovery covers it" (section 4, Connection). The test expects the producer
  to go down, another recovery request after the reconnect, starting where the session had got to
  before the connection went, and the producer back up once that recovery completes.
- **Why:** Messages lost with the old queue stay lost.
- **Pinned by:** `ReconnectScenarioIT.afterAReconnectMessagesFlowAgainAndTheGapIsRecovered`
- **Found:** by test against 0.0.56.

## KD-13 An older message overwrites the status of a newer one

- **0.0.x:** An odds change stamped earlier than one already handled writes its match status -
  scores, status - over the newer one's.
- **1.0:** "A message from the same producer with an older timestamp does not write feed-owned
  fields. It is still built and delivered" (section 4, Caches and loaders, Ownership and
  ordering). A replay session's messages write in the order they come, as on 0.0.x: a match
  played again repeats the timestamps of its last run. As on 0.0.x, a field the new run leaves
  out, such as a winner or a score, keeps the last run's value until a message replaces it.
- **Why:** Out-of-order delivery turns a score back.
- **Pinned by:** `StaleFeedScenarioIT.anOlderMessageDoesNotReplaceTheStatusOfANewerOne`
- **Found:** by test against 0.0.56.

## KD-14 A message from half an hour ago writes the status as current

- **0.0.x:** An odds change stamped 30 minutes ago writes its match status as if it were current.
- **1.0:** "A feed message whose corrected age exceeds the same 20 minutes does not write
  feed-owned fields either. A message that old is a delayed backlog message, and REST has since
  taken over. It is still delivered" (section 4, Caches and loaders); the status comes from the
  match summary.
- **Why:** A backlog replaces fresher state with old.
- **Pinned by:** `StaleFeedScenarioIT.aMessageFromHalfAnHourAgoIsStillDelivered`
- **Found:** by test against 0.0.56.

## KD-15 Closing a feed that failed to start logs an error

- **0.0.x:** `close()` on a feed whose start failed before the bookmaker details loaded logs
  "Failed to close" at ERROR, with a provisioning error for "missing bookmaker detail": the timer
  it shuts down is only created at that point and needs the bookmaker details. By the source,
  what `close()` releases after the timer - the caches and the API client - is left as it is.
- **1.0:** `open()` is all or nothing and closes whatever it created when a step fails; the
  client's exit is `close()` and a new `OddsFeed` (section 4, Connection, and section 3, Behaviour
  that stays). A failed start has released what it built, so that `close()` has nothing to
  release and logs nothing; the test expects nothing logged by it.
- **Why:** Closing after a failure is what clients are told to do; it should not look like a
  second failure.
- **Pinned by:** `StartupScenarioIT.withTheApiDownTheFeedDoesNotStart`
- **Found:** by test against 0.0.56.

## KD-16 A login the broker refuses escapes as the AMQP client's own exception

- **0.0.x:** `open()` throws the AMQP client's `com.rabbitmq.client.AuthenticationFailureException`,
  not an SDK exception; its message carries the broker's `ACCESS_REFUSED`. The SDK tries once and
  does not retry.
- **1.0:** `open()` throws the SDK's `InitException`, saying the broker refused the login or the
  virtual host, with the broker's reason as its cause and the access token taken out of both
  (ticket 21). It tries once: `open()` is all or nothing. Once the feed is open, refusals while
  reconnecting end it only after they have gone on for a minute, with a fatal event.
- **Why:** A client catching the SDK's exceptions misses this one.
- **Pinned by:** `StartupScenarioIT.aTokenTheBrokerRefusesStopsTheStart`
- **Found:** by test against 0.0.56.

## KD-17 Nothing acts on a feed that is far behind

- **0.0.x:** No safety net: a backlog of old messages is processed one by one. The watchdog can
  mark the producer down with `PROCESSING_QUEUE_DELAY_VIOLATION` (from its first run, KD-7), but
  that asks for no recovery; the producer comes back up once messages are on time again.
- **1.0:** The stale-message safety net (section 4, Recovery and producers): when the age of live
  messages from a producer stays above the configured limit for the configured window, a recovery
  from the oldest checkpoint, then the session's channel is replaced; capped at three resets per
  session per cool-down, then the session is marked lagging.
- **Why:** Past a point one snapshot is cheaper than working through the backlog.
- **Pinned by:** none yet; the limit and window are 1.0 settings a test compiled against 0.0.x
  cannot set. To be pinned by a test on 1.0 only, with ticket 28's options; until then the rule
  is covered by the recovery actor's unit tests, which come with ticket 24.
- **Found:** by reading the source (`RecoveryManagerImpl.timerTick`, `systemSessionAliveReceived`).

## KD-18 A producer listed in both scopes has none

- **0.0.x:** The producer list names a producer's scopes as `live|prematch`, and the same literal
  split as in KD-6 leaves such a producer with no scope at all - so, by the source, a live-only or
  prematch-only session never enables it and drops its messages.
- **1.0:** The scopes are split: a producer listed as `live|prematch` has both (ticket 23).
- **Why:** A producer with no scope is lost to every session that names one.
- **Pinned by:** `ProducerRecoveryScenarioIT.aProducerListedWithBothScopesHasBoth`
- **Found:** by test against 0.0.56; the effect on sessions by reading the source
  (`MessageInterest`).

## KD-19 The feed's broker connection trusts any certificate

- **0.0.x:** The AMQP connection is made with `useSslProtocol()` and no trust of its own, which
  in the client 0.0.x uses accepts any certificate and checks no host name.
- **1.0:** The broker's certificate is checked against the JVM's default trust, and its host name
  against the certificate (ticket 21). A client with a truststore of its own, or behind a proxy that
  inspects TLS, gives its own context with `OddsFeedConfigurationBuilder.setMessagingSslContext`. A
  broker that fails the check fails `open()` with an `InitException` saying it could not be
  trusted.
- **Why:** Over a connection that trusts any certificate, anyone on the path can take the login,
  whose user name is the access token.
- **Pinned by:** none; the fake broker's certificate is trusted by the JVM default in every test.
- **Found:** by reading the source (`AMQPConnectionProvider`).

## KD-20 Home and away are taken by position, and only for a classic match

- **0.0.x:** `Match.getHomeCompetitor()` is the first competitor the API lists and
  `getAwayCompetitor()` the second, whatever their `qualifier`. For a match whose `sport_format`
  is not `classic` - `esports`, which the API's fixtures carry, reads as `UNKNOWN` - or that does
  not have exactly two competitors, both throw an `IllegalArgumentException` under `THROW` and
  return null under `CATCH`.
- **1.0:** Home is the competitor qualified `home` and away the one qualified `away`, in whatever
  order the API lists them, for any format but a race (ticket 20). `getSportFormat()` still reads
  `esports` as `UNKNOWN`. A race, or a match without one competitor of each qualifier, has neither,
  and both return null under either strategy: there is nothing that failed to load.
- **Why:** A summary listing away first swaps the teams, and every esports match has no home or
  away at all.
- **Pinned by:** none; the fixtures the system tests use list home first, as a classic match.
- **Found:** by reading the source (`MatchImpl.homeAwayCompetitor`).

## KD-21 The feed's winner is dropped

- **0.0.x:** The `winner_id` an `odds_change` carries in its `sport_event_status` is dropped: the
  0.0.x binding of that element has no such attribute. `MatchStatus.getWinnerId()` is the match
  summary's: taken from a summary loaded for any reason, and loaded for the status only once it
  has gone 20 minutes without a write. A settlement right after the closing odds change reads no
  winner for a match whose summary was loaded before it ended.
- **1.0:** The winner is in the live state with the other feed-owned fields (NEXT.md section 4,
  Caches and loaders, Ownership and ordering). A message that carries one writes it, and a message
  without one keeps it. The summary retracts it once the feed has been quiet for the match status
  age; while the feed is live, a summary's winner fills one the feed has not sent. The Go and .NET
  SDKs take the feed's winner too.
- **Why:** A client that settles bets on the winner gets it with the settlement, not 20 minutes
  later.
- **Pinned by:** `MatchStatusScenarioIT.aSettlementRightAfterTheClosingOddsChangeReadsTheFeedsWinner`
- **Found:** by test against 0.0.57, and in the source (`MatchStatusCache.applyFeedSnapshot`,
  `OFSportEventStatus`).

## KD-22 A getter of several locales fails when one of them cannot load

- **0.0.x:** An entity read in several locales loads each one it does not hold, skips one
  that fails, and answers from the others: a match in English and German, with the German
  summary failing, still has its scheduled time and its English name, and a match status
  description is the one of the locales it holds.
- **1.0:** The locales load side by side, and a getter of every locale fails as a whole when
  one fails: an `ItemNotFoundException` under `THROW`, null under `CATCH`. A match status
  description is null. A getter of one locale is not affected (NEXT.md section 3, Behaviour
  that stays: never a partial collection).
- **Why:** An answer from part of the locales reads as complete.
- **Pinned by:** none; the system tests read every entity in one locale, but the unit tests
  pin it (`MatchViewTest`, `ProfileViewsTest`, `StatusDescriptionsTest`).
- **Found:** by reading the source (`Cache.loadFromCache`, `LocalizedStaticDataCache.get`).

## KD-23 Closing the feed is reported as the connection down

- **0.0.x:** `onConnectionDown` is called whenever the AMQP connection shuts down, also when the
  client's own `close()` shuts it: the SDK's shutdown listener does not look at who closed it.
- **1.0:** Called for a connection lost, not one the feed closed: "No duplicate 'down' on a normal
  close" (NEXT.md section 4, Connection). The events dispatcher calls it when the transport reports
  a loss, which it does not on `close()` (ticket 22).
- **Why:** A client that alerts on a lost connection alerts on every shutdown and every deploy.
- **Pinned by:** `ReconnectScenarioIT.closingTheFeedIsNotReportedAsTheConnectionDown`
- **Found:** in the source (`AMQPConnectionProvider`, `addShutdownListener`), and by test against
  0.0.57.

## KD-24 American odds from 2 are the decimal odds less 100

- **0.0.x:** `OutcomeOdds.getOdds(OddsDisplayType.AMERICAN)` gives the decimal odds less 100 for
  odds of 2 and over: 2.5 reads -97.5. Under 2 it gives `-100 / (odds - 1)`, which is right; 1 reads
  null, NaN reads NaN, and odds under 1 read as a positive number.
- **1.0:** The moneyline odds, as the Go SDK gives them: `(odds - 1) * 100` from 2 (2.5 reads 150),
  `-100 / (odds - 1)` under 2, and null for odds of 1 or less or not finite, which have no American
  odds (ticket 22).
- **Why:** Every American price of an underdog is wrong, and negative where it should be positive.
- **Pinned by:** `OddsChangeScenarioIT.americanOddsAreMoneylineOdds`
- **Found:** in the source (`OutcomeOddsImpl.convertOdds`, which subtracts `1.0 * 100`), and by test
  against 0.0.57.

## KD-25 A lost snapshot complete keeps a producer down for six hours

- **0.0.x:** A recovery whose `snapshot_complete` never arrives is asked for again only once the
  maximum recovery time has passed: 360 minutes, with no setter. The producer stays down
  meanwhile. The .NET SDK does the same, with a setter.
- **1.0:** A producer's recovery waits five minutes at most for its `snapshot_complete`, counted
  from the request, or from the last message of its snapshot, or message or alive sent before the
  request, or of an earlier recovery of the producer that failed or was given up, that a session
  awaiting it took; an event recovery's snapshot message counts only when that event recovery was
  asked for first. Then it is asked for again with backoff, as one the API refused
  (KD-11). The maximum recovery time still bounds a recovery that keeps coming, and event
  recoveries (NEXT.md section 4, Recovery and producers).
- **Why:** The Go SDK saw a `snapshot_complete` that never arrived on a bound, consuming queue;
  until the recovery is given up, the client drops or buffers the producer's live feed.
- **Pinned by:** none in the system tests; the fake feed cannot lose one message of a recovery,
  and a scenario of five minutes is not worth it. The recovery actor's unit tests pin the
  deadline.
- **Found:** by reading the source (`RecoveryManagerImpl.systemSessionAliveReceived`), after the
  Go SDK's unmerged fix for it.

## KD-26 An unsubscribed alive during a recovery asks again at once

- **0.0.x:** Every alive with `subscribed="0"` asks for a new recovery at once, which replaces
  the one in flight: an alive sent before the producer saw the request replaces it too, and so
  does every further alive while the producer stays unsubscribed. A producer silent for longer
  than the maximum inactivity interrupts the recovery, and its next alive asks again.
- **1.0:** One recovery in flight per producer. An unsubscribed alive that arrives after the API
  accepted the recovery means the producer lost it: the recovery is given up, uncounted, and the
  next alive asks again, about one alive interval later. One that arrives before the API's
  answer joins the recovery, and one more is asked for once it completes. Silence gives the
  recovery up, and the next alive asks again, as in 0.0.x (NEXT.md section 4, Recovery and
  producers).
- **Why:** A client that counts recovery requests, or watches their ids, sees fewer of them, and
  the new request ten seconds later than on 0.0.x.
- **Pinned by:** none in the system tests; the recovery actor's unit tests pin it.
- **Found:** by reading the source (`RecoveryManagerImpl.systemSessionAliveReceived`).

## KD-27 A channel the broker takes on a live connection is not opened again

- **0.0.x:** The AMQP client's automatic recovery opens a lost connection again, with its
  channels, but not a channel the broker closes or a consumer it cancels while the connection
  stays up - a deleted queue, say. The session then receives nothing more, and nothing is
  recovered. The SDK's alive-only queue still gets the alives, so the producer stays up until
  the watchdog runs (KD-7); then the session's missing alives take it down with
  `PROCESSING_QUEUE_DELAY_VIOLATION`, which asks for no recovery, and it stays down.
- **1.0:** The transport opens such a channel again, tells the session of the loss and of the
  new channel, and recovery covers what the old queue held, once the new one is bound (NEXT.md
  section 4, Connection, and Recovery and producers). The test expects the producer to go down,
  the second recovery to start where the session had got to before the loss, and the producer
  back up once that recovery completes.
- **Why:** A session that silently stops receiving keeps its producers up with stale state,
  and once the watchdog notices, down for good: only a restart brings them back.
- **Pinned by:** `ReconnectScenarioIT.afterTheBrokerTakesTheSessionsQueueMessagesFlowAgainAndTheGapIsRecovered`
- **Found:** by reading the source (`ChannelConsumer`, `AMQPConnectionProvider`) and the AMQP
  client's documentation of automatic recovery, which a channel-level error does not start;
  confirmed by test against 0.0.57, the watchdog's part in a run of two minutes.

## KD-28 A producer's recovery timestamp runs ahead of what was processed

- **0.0.x:** `Producer.getTimestampForRecovery()` reports the generation time of the last alive
  on the SDK's alive channel while the producer is up, and the client's recovery-from timestamp
  before the first. That alive is newer than the messages still queued, or in a callback, when
  the client stops: a client that persists the value and passes it back after a restart never
  gets those messages again.
- **1.0:** The client's value until the feed opens; then the producer's resume point, the oldest
  point any session that receives the producer still needs: the oldest start of a gap no
  recovery has covered yet, or the oldest point up to which a session has processed the
  producer's messages. It is not clamped to the stateful recovery window, so after a long
  downtime `setProducerRecoveryFromTimestamp` refuses it, as 0.0.x refuses any timestamp that
  old; the getter's documentation says to pass 0 then (NEXT.md section 4, Recovery and
  producers).
- **Why:** Resuming from 0.0.x's value can miss messages without a word; resuming from 1.0's can
  repeat some, and misses none. A client that reads the value to see whether the producer is
  alive sees it fall behind, or stand still while a gap is open.
- **Pinned by:** `ProducerRecoveryScenarioIT.theRecoveryTimestampIsWhatTheSessionProcessedNotALaterAlive`,
  with a session in the callback of a message after the one it processed and an alive after
  both; the recovery machine's and the producers' unit tests pin the rest.
- **Found:** in the source (`ProducerImpl.timestampForRecovery`,
  `ProducerRecoveryData.systemAliveReceived`).

## KD-29 A session built after open() never receives anything

- **0.0.x:** The session builder's `build()` and `buildReplay()` return a session after `open()`
  as before, but only `open()` binds sessions to the feed, so the session never gets a message,
  and nothing says so.
- **1.0:** `build()` and `buildReplay()` throw `IllegalStateException` once the feed is open:
  "the feed is already open; build sessions before open()". `open()` binds every session's queue
  once (NEXT.md section 3, difference 7).
- **Why:** A session that silently receives nothing looks like a quiet feed; the client finds
  out only when it misses what it was built for.
- **Pinned by:** `SessionScenarioIT.aSessionBuiltAfterOpenIsRefused`
- **Found:** by reading the source (`OddsFeedSessionBuilderImpl.build`, `OddsFeed.open`), and by
  test against 0.0.57.

## KD-30 An event recovery asked for before open() is sent, and what it recovers is lost

- **0.0.x:** `initiateEventOddsMessagesRecovery` and `initiateEventStatefulMessagesRecovery`
  before `open()` send the request at once, after the feed's lazy start, and return its request
  id. No session's queue exists yet, so the messages the recovery sends reach no queue and are
  lost, and nothing says so.
- **1.0:** Before `open()` both return null and log a warning; nothing is sent (section 3,
  Behaviour that stays: "an event recovery asked for before it is not accepted (null), since no
  queue exists yet for its messages"). The Go SDK refuses too, with an error saying the manager is
  not open, which a caller may retry.
- **Why:** A request id the client keeps for a recovery whose messages can never arrive is a
  recovery it waits for in vain.
- **Pinned by:** `BeforeOpenScenarioIT.anEventRecoveryAskedForBeforeOpenIsNotSent`
- **Found:** by reading the source (`SDKRecoveryManagerImpl.makeEventRecovery`, `OddsFeed.open`).

## KD-31 Raw API data before open() does not reach the extended listener

- **0.0.x:** The extended listener's `onRawApiDataReceived` is subscribed only inside `open()`
  (`ApiClient.subscribeForData`), so the responses of the calls a client makes through the
  managers before it, and of the feed's own start, are not handed to it.
- **1.0:** The events dispatcher starts with the feed, on the first call that starts it, so the
  extended listener gets the raw data of every response from then on, before and after `open()`,
  and the global listener's `onApiCall` hears every call as it is made, as the Go SDK reports its
  API events from the start. Additive: a client gets more, never less.
- **Why:** A client that logs or audits the raw API data misses what came before `open()`, the
  start's own whoami and producer list among it.
- **Pinned by:** `BeforeOpenScenarioIT.rawApiDataBeforeOpenReachesTheExtendedListener`
- **Found:** by reading the source (`OddsFeed.open`, `ApiClientImpl.subscribeForData`).

## KD-32 A recovery-from timestamp set before open() is forgotten

- **0.0.x:** `setProducerRecoveryFromTimestamp` writes the timestamp into the producer list the
  managers loaded, and `open()` fetches the list again and replaces it, so a timestamp set before
  `open()` is lost: the first recovery asks for a full snapshot, or for the initial snapshot
  interval when one is configured.
- **1.0:** The timestamp seeds every session's checkpoint at `open()`, and the first recovery
  starts from it, clamped to the producer's stateful recovery window (NEXT.md section 4,
  Recovery and producers).
- **Why:** A client that persists the recovery point and passes it back after a restart gets a
  full snapshot instead of what it missed.
- **Pinned by:** `ProducerRecoveryScenarioIT.theRecoveryTimestampTheClientSetsIsWhereTheFirstRecoveryStarts`
- **Found:** by test against 0.0.57; the source (`OddsFeed.open`, `ProducerManagerImpl.open`)
  explains it.
