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
- **1.0:** To be decided. Difference 3 in NEXT.md section 3 covers attributes the feed never
  sends; `void_reason` is one it sends.
- **Why:** A client still on the deprecated getter gets nothing.
- **Pinned by:** `FeedMessageScenarioIT.aBetCancelReachesOnBetCancelWithItsWindowAndVoidReasons`
- **Found:** by test against 0.0.56, and in the source (`MarketCancelImpl`).

## KD-6 Lists separated by a pipe are not split

- **0.0.x:** Kotlin's `split("\\|")` takes its argument literally, so it splits on the two
  characters `\|`, not on `|`. A bet stop with `groups="winner|handicap"` has one group,
  `winner|handicap`. By the source, a producer whose scope is `live|prematch` has no scope at
  all - so a live-only or prematch-only session never enables it and drops its messages.
- **1.0:** To be decided. NEXT.md is silent; the schema documents the groups as a list separated
  by a pipe.
- **Why:** A client matching bet stop groups one by one never matches.
- **Pinned by:** `FeedMessageScenarioIT.aBetStopForSeveralGroupsNamesEach`
- **Found:** by test against 0.0.56 for the groups; the scopes and their effect on sessions by
  reading the source (`ProducerData`, `MessageInterest`).

## KD-7 The producer watchdog first runs a minute after open

- **0.0.x:** The recovery watchdog runs first 60 s after `open()`, then every 10 s. Its first run
  reports every producer without an alive in the last 20 s down with `ALIVE_INTERVAL_VIOLATION`,
  including producers that were never up.
- **1.0:** To be decided.
- **Why:** Any scenario longer than a minute sees these status changes on top of what it drives;
  `ReconnectScenarioIT` stays under the minute because of it.
- **Pinned by:** none; a minute-long scenario in every run is not worth it.
- **Found:** by reading the source (`RecoveryManagerImpl.open`, `timerTick`), and seen once in a
  scenario, not kept, that opened the feed and waited 75 s: both producers reported down with
  `ALIVE_INTERVAL_VIOLATION` 60 s after `open()`, neither of them ever up.

## KD-10 Under CATCH a collection that cannot load is empty

- **0.0.x:** `Match.getCompetitors()` under `ExceptionHandlingStrategy.CATCH`, for a match the
  API cannot serve, returns an empty list.
- **1.0:** Returns null: "under CATCH the whole collection is null" (section 3, Behaviour that
  stays).
- **Why:** An empty list reads as "no competitors" rather than "could not load".
- **Pinned by:** `ExceptionStrategyScenarioIT.underCatchAGetterTheApiCannotServeReturnsNull`
- **Found:** by test against 0.0.56.

## KD-13 An older message overwrites the status of a newer one

- **0.0.x:** An odds change stamped earlier than one already handled writes its match status -
  scores, status - over the newer one's.
- **1.0:** "A message from the same producer with an older timestamp does not write feed-owned
  fields. It is still built and delivered" (section 4, Caches and loaders, Ownership and
  ordering).
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

## KD-17 Nothing acts on a feed that is far behind

- **0.0.x:** No safety net: a backlog of old messages is processed one by one. The watchdog can
  mark the producer down with `PROCESSING_QUEUE_DELAY_VIOLATION` (from its first run, KD-7), but
  that asks for no recovery; the producer comes back up once messages are on time again.
- **1.0:** The stale-message safety net (section 4, Recovery and producers): when the age of live
  messages from a producer stays above the configured limit for the configured window, a recovery
  from the oldest checkpoint, then the session's channel is replaced; capped at three resets per
  session per cool-down, then the session is marked lagging.
- **Why:** Past a point one snapshot is cheaper than working through the backlog.
- **Pinned by:** none yet; the limit and window are 1.0 settings a test compiled against 0.0.56
  cannot set. To be pinned with ticket 24.
- **Found:** by reading the source (`RecoveryManagerImpl.timerTick`, `systemSessionAliveReceived`).
