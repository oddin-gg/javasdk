# Benchmarks

JMH benchmarks of the SDK's hot path, with a budget per message that `./mvnw verify` checks.

## What runs

- `DecodeBenchmark` decodes one odds change, warm, three ways: the SDK's decoder (JAXB on
  Woodstox), JAXB on the JDK's own parser - the decoder as it was until 2026-10-01 - and a
  hand-written StAX reader into the same generated classes, on the JDK's parser. Each over
  three sizes: 20, 150 and 500 markets.
- `WarmPathBenchmark` runs the steps after the decode, warm, over the same odds changes:
  `cacheWrite` writes the message's match status to the match's live state, as a session's
  dispatcher does before the callback, and `entityBuild` builds the message the client gets - its
  event and every market and outcome. Names are read from the catalog only when a client asks, so
  neither step calls the API.
- The corpus is generated (`Corpus`): odds changes shaped like a live match's, with a status,
  period scores, a scoreboard and markets of two to twenty outcomes, three in four with
  specifiers. It is the same every time for a size, and `CorpusTest` checks it against the
  feed schema. There is no recorded traffic in the repository.
- `StaxOddsChangeReaderTest` holds the StAX reader to decoding exactly what JAXB decodes,
  over the corpus and every vendored odds change fixture, so the comparison compares the
  same work.
- `BudgetTest` runs the SDK's decoder and the warm path's steps, and fails when one goes over
  `budgets.properties`:
  allocation per message within 1.5 times the measurement, time within 5 times and never under
  10 µs, where a runner's noise weighs as much as a step of a microsecond or two. Allocation
  is the tight check, since it hardly moves between machines; time only catches a decoder
  that became several times slower. A slip back to the JDK's parser stays within both, so
  `XmlReaderTest` in `odds-feed` holds the decoder to Woodstox. JMH forks its own JVM, so the build's coverage agent
  does not run inside the measurement.

Only the SDK's decoder and the warm path's steps have a budget, so the build runs only them, and
fails when one of the budgets went unmeasured. A run narrowed with `-Dbenchmarks.include` holds
what it ran to its budget and skips that check. For the comparison:

```
./mvnw -pl odds-feed,benchmarks -am test -Dtest=BudgetTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbenchmarks.include=DecodeBenchmark -Dbenchmarks.iterations=10
```

## Numbers

2026-10-01, Apple M-series, JDK 25, per odds change, warm:

| markets | JAXB on Woodstox (the SDK) | JAXB on the JDK's parser | StAX, hand-written |
|--------:|---------------------------:|-------------------------:|-------------------:|
| 20 | 57 µs, 81 KB | 150 µs, 81 KB | 50 µs, 91 KB |
| 150 | 329 µs, 456 KB | 891 µs, 396 KB | 295 µs, 330 KB |
| 500 | 1.12 ms, 1.52 MB | 2.97 ms, 1.29 MB | 0.97 ms, 1.02 MB |

The JDK's own StAX parser was the cost, not JAXB: on Woodstox, JAXB is about three times as
fast and about a sixth (11-16 %) slower than the hand-written reader, with its name limit and per-document name
table counted in, which is why the decoder stays on JAXB and
the generated classes, with no parsing code to keep in step with the schema. It allocates
somewhat more. On 2026-09-29, before the change, the SDK's column read as the middle one;
keeping one unmarshaller instead of one per message changed nothing.

2026-10-14, on the Linux box the cold scenario runs on (Intel Pentium Gold 8505, JDK 25, other work on the
machine: a load average of 2 to 4), 10 iterations, per odds change, warm:

| markets | JAXB on Woodstox (the SDK) | JAXB on the JDK's parser | StAX, hand-written |
|--------:|---------------------------:|-------------------------:|-------------------:|
| 20 | 69 µs, 81 KB | 127 µs, 79 KB | 52 µs, 88 KB |
| 150 | 398 µs, 447 KB | 734 µs, 386 KB | 293 µs, 322 KB |
| 500 | 1.39 ms, 1.49 MB | 2.43 ms, 1.26 MB | 0.99 ms, 1.00 MB |

The times are a fifth to a quarter above the M-series ones, the allocation the same, so the decoder costs about
what it did. The ratios differ with the machine: here Woodstox is 1.8 times as fast as the JDK's parser
(not three) and JAXB 33 to 40 % slower than the hand-written reader (not a sixth), both from one run with other
work on the machine; the conclusion - the parser was the cost, and JAXB on Woodstox stays near the
hand-written reader - holds on both. A second run a few minutes later, with the load average above four,
read 37 % and 92 % slower at 150 and 20 markets, and the same at 500: a figure from this box is a run on a shared machine.

2026-10-05, the same machine, the warm path's steps after the decode, per odds change:

| markets | cache write | entity build |
|--------:|------------:|-------------:|
| 20 | 0.4 µs, 2.5 KB | 1.7 µs, 16 KB |
| 150 | 0.4 µs, 2.5 KB | 12.8 µs, 112 KB |
| 500 | 0.4 µs, 2.5 KB | 50 µs, 380 KB |

On the Linux box, 2026-10-14, the same steps: the cache write 0.5 µs and 2.5 KB whatever the size, the
entity build 1.9 µs and 16 KB at 20 markets, 12.8 µs and 113 KB at 150, 48 µs and 384 KB at 500.

Both are small next to the decode: a typical live odds change of 150 markets spends about 330 to 400 µs
decoding, 13 µs being built and under a microsecond in the cache.

## The cold scenario

`ColdStartBudgetTest` is a restart: a feed opens against the fake API, which answers every request
after a fixed 50 ms, with catalogs larger than the production API's (600 market descriptions and 250
sports: room on purpose, and the corpus's market ids run to 400), and 200 matches nothing is cached
for each get
their first odds change at once, with the eager entity preload on and English as the preload locale.
A callback reads the match's name, as a client does.

- Time to caught up: from the first publish until every callback ran and the requests they caused are
  done. This decides whether a restarted client trips its safety net.
- First message: one odds change for a cold match on a fresh feed, until its callback has read the
  match's name and a market's.

Measured on this Linux box (Intel Pentium Gold 8505, JDK 25, Docker for the broker; 2026-10-14, the same
session as the warm numbers above), median of 7 runs:

| | time |
|---|---:|
| caught up, 200 matches | 3.9 s (3.90 to 3.96 s), of which 1.6 s after the last publish (1.5 to 2.0 s) |
| first message | 61 ms (58 to 66 ms) |

The first message read 152 ms (71 to 161) before the benchmark waited for the catalogs the open preloads: a
message that came while the market list was still on its way made a fetch of its own.

The 600 requests the 200 matches cost (a summary and two competitor profiles each) are answered
8 at a time, by the side-load workers the preload shares - half the REST concurrency limit, 16 by
default - so 50 ms each is 3.75 s: the pace is the workers', not the broker's. Before the workers
followed the limit there were four fixed ones, and the same restart took 7.9 s. With the preload
off, the same 40 matches take 2.7 times as long (2.3 s against 0.85 s), the callbacks loading them
one by one.

The budget (`cold-budgets.properties`) is the measurement with room, 3 times, and the requests are
counted exactly: a match costs one summary and one profile per competitor, nothing else, and each
catalog is read once. The time is mostly the injected latency, so it holds on a shared runner; a
change that makes a match cost another request, or keeps the preload from overlapping, fails the
build. One thing is not measured: how often a competitor is already cached in production (here every match
brings two new ones, the worst case). The JVM is warm from the other tests, so this is the REST and
the caches cold, not a JVM that has just started.

### The variant mode

A client's callbacks often read market names, and a market with a variant of dynamic outcomes (the
players of one match, in a player-props market) has its description in a request of its own, per
variant, the first time its name is read - on the thread of the callback that reads it. This mode is
the same restart with every odds change carrying 5 such markets after the corpus's 150, each with a
variant of its own (no two matches share one), and every callback reading the name of every market of
its message. The fake API answers the variant's endpoint from the generator, after the same 50 ms.
The corpus's one other variant specifier is left out in this mode: it is a variant the generated list
has no row for, which would fail the name read. The sizes are the generator's: 5 variants a match is
not a production figure.

Measured on the same box, median of 7 runs, with the 200 matches (2026-10-14: 52.4 s, 51.5 to 54.3 s; earlier
the same day 53.2 s, 53.0 to 54.8 s):

| | variant mode |
|---|---:|
| caught up | 52.4 s (51.5 to 54.3 s), of which 49.0 to 52.2 s after the last publish |
| requests | 1606: the 606 above and 1000 variants, each once |
| most answered at once | 9 |
| longest callback | 0.31 to 0.37 s (5 variants and the plain names) |
| longest single name read | 59 to 81 ms |
| the feed's health, health events | healthy, none |

The pace is not the catalog's two permits. The callbacks of a session run one after another, and
each read of a cold variant is one request that the reader waits for, so the variants are fetched one
at a time: 1000 variants of 50 ms are 50 s, and what the preload does in parallel (the 606 requests,
done in 3.75 s) is hidden behind it, which is why the worker count did not move this figure (53.0 s
before). The time follows the number of variants and the latency, not the matches: 50 matches took
1.1 s with no variant (only the plain names read), 13.7 s with 5 variants each (250 variants) and
53.5 s with 20 (1000 variants), a latency each and about 1 s besides (median of 3 runs); so 20
variants in each of 200 matches would take about 200 s. No read was near the SDK's deadline for one request (30 s: the longest took
0.1 s), and no callback near the stall limit of the health check (65 s: the longest, 20 variants, took
1.2 s), so the feed stayed healthy and no health event was told. What it costs is the wait: a
callback that reads 20 variants holds its session's thread for about a second, and every message
waits behind the messages before it.

The budget for the mode is the measurement with the same room, 3 times
(`cold.variants.caughtUp.ms`), and the requests are counted exactly: a variant market costs one
request the first time and none after, and none at all when the callbacks do not read its name.

It needs Docker, as the system tests do: the fake feed is a RabbitMQ container. Without Docker the
tests skip with a message, unless the environment variable `CI` is set, where they fail.
`-Dbenchmarks.cold.matches=`, `-Dbenchmarks.cold.runs=` and `-Dbenchmarks.cold.variants=` change
the burst, the repeats and the variant mode's variants a match.

## The test environment

`TestEnvironmentRestartTest` is the cold scenario against the real thing: the 1.0 SDK opens against the
test environment, which has live matches, and the harness measures what the SDK saw of the restart,
through its public API only - the time to the first answers, each endpoint's latency from the API call
events, when every producer is up, and when each session has caught up. It publishes nothing, does not
replay and makes no calls but the SDK's own at start, the recovery requests every client sends, and the
calls the callbacks cause. It is skipped unless `BENCHMARKS_TESTENV=test` (or the system property
`benchmarks.testenv`) is set, never runs where `CI` is set, and only the test environment can be named.
The access token is read from `ODDIN_FEED_TOKEN_TEST` and goes to the SDK's configuration and nowhere
else.

```
BENCHMARKS_TESTENV=test BENCHMARKS_TESTENV_READS=names ODDIN_FEED_TOKEN_TEST=... \
    ./mvnw -pl benchmarks -am verify -Dtest=TestEnvironmentRestartTest -Dsurefire.failIfNoSpecifiedTests=false
```

`BENCHMARKS_TESTENV_READS` is `nothing` (default) or `names`: the callbacks read the match's name and
every market's name of an odds change, variants included. Also `_SESSIONS` (message interests, `ALL`
by default), `_EAGER` (the eager preload, on), `_LAG` (seconds, 5), `_SETTLE` (30), `_MAX` (minutes, 12)
and `_LABEL`. The report goes to the console and to `benchmarks/target/testenv/`. A session has
caught up from the last message that was older than the lag limit when its callback started, beyond the
least a message ever took from the producer to this client, which takes out the producer's clock and the
network; the report also has the time a message waited inside the SDK, on this machine's clock only.
The run ends once every producer is up and every session has been on time for the settle time.

Six runs, three of each mode, a few minutes apart, 2026-10-14, from a Linux box:

- The first answers come about half a second after the start (the first request carries the TLS
  handshake), the broker connection and `open()` after two to three seconds, and the catalogs the open
  preloads are answered within a fifth of a second of it. A REST call answers in about 35 ms (the market
  list and the sports list in about 100 ms), with no retry and no failure.
- A restart is paced by the environment, not by the SDK. The recovery is asked for at the producer's first
  alive, up to about 10 s after the start, and the producer's snapshot then takes 12 to 20 s to complete;
  every producer is up and every session caught up there, 14 to 30 s after the start. The messages the
  snapshot brings are the old ones; the SDK's own wait for a message stayed under 50 ms and nothing
  queued with no name read.
- With the callbacks reading every market's name, variants included, each cold variant cost a request of
  that latency, on the thread of the callback that read it: the longest callback took about 0.7 s, a
  message waited up to 0.8 s inside the SDK, and the queue of the session grew to a few dozen messages
  and emptied. The time to every producer up and every session caught up did not move: it varied between
  runs more than between the two modes. The environment has few variants, so they added a second or so
  of reading time to a restart of tens of seconds.

Reading the same names against a catalog of variants as large as production's is the variant mode above:
the callbacks run one after another, so the variants are fetched one at a time, a latency each, and a
restart whose callbacks read many variant names is paced by them, whatever the preload does in parallel.
This is 0.0.x's and the Go SDK's behaviour and 1.0 keeps it (decision of 2026-10-14): a cold variant name is
one request, on the thread that reads it. A client that restarts with live matches and needs the names
avoids the pace by keeping the reads off the session's thread - handing the message to its own workers,
which fetch several variants at a time - by reading names only for the markets it shows, and by preloading
the locales it reads (`setPreloadLocales`), which leaves the plain markets as one list read at the open,
so that only the variants are requests of their own. Nothing in the SDK reads a name the client does not ask for.

## Sustained load

### The test environment, for an hour

`BENCHMARKS_TESTENV_DURATION=<minutes>` turns the test environment run into a sustained one: the same feed, left
running for that long instead of ending once every session is on time. It runs as `aSustainedRun` of
`TestEnvironmentRestartTest`, takes the same settings and the same token, and adds to the report the time
through a session message by message (the SDK's wait plus the callback), in windows of a twelfth of the run; the
feed's health every minute; and what the JVM spent - the heap allocated, the process's CPU, the threads, and the
GC pauses, which come from the GC log, to the microsecond, so the JVM needs `-Xlog:gc:file=<path>:uptime` and
`-Dbenchmarks.gclog=<path>`:

```
BENCHMARKS_TESTENV=test BENCHMARKS_TESTENV_DURATION=60 ODDIN_FEED_TOKEN_TEST=... \
    ./mvnw -pl benchmarks -am verify -Dtest=TestEnvironmentRestartTest#aSustainedRun \
    -Dsurefire.failIfNoSpecifiedTests=false '-DargLine=-Xmx512m -Xlog:gc:file=gc.log:uptime -Dbenchmarks.gclog=gc.log'
```

Sixty minutes, 2026-10-14, from the Linux box, one session with every message interest and a callback that reads
nothing, a 512 MB heap:

- After the recovery's snapshot the environment sends about a message a second, so this shows the cost of a
  message and the state of a feed that has nothing to do, not a load.
- The time through the session: p50 1 ms, p99 4 ms, p99.9 44 ms, longest 58 ms. The tail is the first minutes (the
  snapshot and the JIT); every later window of five minutes has a p99 of 2 to 3 ms and a longest of 6 ms. The callback
  took no measurable time.
- The feed's health was healthy at every one of the sixty samples, the session's queue empty at every one, no health
  event was told, and no API call was retried or failed.
- The JVM allocated about 0.2 MB a second after the open, paused for the GC five times in the hour (the longest 11 ms)
  and held 18 MB after the last of them: nothing grew. Its threads were 30 to 33 from the first minutes to the end
  (the count alternates between the two), and no group of them by name grew but one of the broker client's consumer
  threads, two after the open and three at the end (three after three minutes in a shorter run too), which is no growth
  over the hour. The process used 0.01 of a core.

### Against the fakes, 0.0.58 and 1.0

The system tests' `LoadHarnessIT` (`system-tests`, skipped unless `SYSTEMTESTS_LOAD=restart`, `rate` or `all`, and
never where `CI` is set) puts the same load on whichever SDK the build has on its classpath - the published 0.0.58
by default, 1.0 with `-Dsdk.next` - through the public API the two share, so the numbers compare. The SDK runs in a
JVM of its own, with a fixed 1 GB heap and the GC log on, so that the allocation, the GC and the CPU it reports are
its own and its listener's; this JVM runs the fake API, the fake feed's broker and the publisher, on the same machine.
The corpus and the catalogs are the benchmarks' (production-sized: the cold scenario's), the fake API answers each
request after 50 ms, and a listener reads what a client forwarding prices does - the match's id and every market's id
and every outcome's odds. Messages are published over AMQP, from several threads, at a steady rate: the fake feed's own
publisher, one request to the broker's management API a message, stops at about 200 odds changes a second.

```
SYSTEMTESTS_LOAD=restart ./mvnw -pl system-tests -am verify -Dsdk.next -Dtest=NoSuchTest -Dit.test=LoadHarnessIT \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

(the same without `-Dsdk.next` for 0.0.58; `SYSTEMTESTS_LOAD_RATE`, `_DURATION`, `_WARMUP`, `_MIX`, `_MATCHES`, `_READS`,
`_HEAP`, `_LABEL`, `_JFR` (a Flight Recorder file of the SDK's JVM, read with `jfr print`) and `_JVM_ARGS` change the run, see the class). The reports go to `system-tests/target/load/`.

A restart: the feed opens, the producers say they are alive, 200 cold matches' odds changes arrive before the
producers' snapshot completes, every callback reads its match's name. Three runs each, 2026-10-14, the Linux box:

| | 0.0.58 | 1.0 |
|---|---:|---:|
| every producer up, from the feed's start | 13.3 s (13.26 to 13.37) | 3.8 s (3.77 to 3.97) |
| the callbacks of the 200 matches done | at the same moment | at the same moment |
| REST requests | 1,500 to 1,550: a summary and about one competitor profile a match, and a profile for each player of a match (about five a match here), answered 3 at a time | 608: a summary and two competitor profiles a match, answered 8 at a time |
| a callback (it reads the match's name) | 53 to 54 ms, p99 60 to 67 ms: a request of its own, one at a time | p50 0.3 to 2.4 ms, p99 38 to 46 ms: most read what the preload loaded |

1.0 is 3.5 times faster to every producer up, with the preload and the side-load workers that 0.0.58 does not have,
and makes about 40 % of the requests: it reads a player's profile when a client asks, 0.0.58 reads the players of a
match with the match.

A steady rate: 1000 odds changes a second for 10 minutes after a minute of warm-up, in a mix of sizes (70 % of 20
markets, 28 % of 150, 2 % of 500: 24 KB on average, 24 MB a second), 70 % from the live producer; both SDKs
handled every message (599,989 to 599,999 of the 600,000 in the window, the rest at its edges), with nothing waiting at
the broker. Two runs each (the machine's load average was 3.6 to 4.6 at their start, other work on it):

| | 0.0.58 | 1.0 |
|---|---:|---:|
| age, from the producer's stamp to the callback, p50 / p99 | 1 to 3 ms / 51 ms (10.3 s in the first run: see below) | 1 to 2 ms / 61 to 65 ms |
| wait in the SDK, from the broker client's hand-over to the callback: p50 / p99 / longest | 0 / 3 to 4 / 30 to 67 ms | 1 / 19 / 74 to 78 ms |
| allocated per message | 281 KB | 297 KB (6 % more; 317 to 318 KB, 13 % more, before the changes under "Allocation against 0.0.58") |
| process CPU per message | 590 to 640 µs | 671 to 680 µs (5 to 15 % more) |
| GC pauses: p99 / longest | 3.2 to 11.7 / 4.2 to 15.1 ms | 3.3 to 3.7 / 3.5 to 5.8 ms |
| heap after a GC, over the run | 16 to 54 MB | 22 to 59 MB |
| threads | 40 | 27 to 37 |

(the allocation row is from runs of 2026-10-15, four of 0.0.58 and three of 1.0 after the changes, 280 to 281 KB and 296.8
to 297.1 KB; the other rows are the two runs above, from before them.)

Both keep up with room (0.6 to 0.7 of a core), the pauses are a few milliseconds and 0.1 % of the time, and the heap
after a GC rises the same way in both - the young collections do not take the old generation - so neither run shows
a leak or a pause that matters. The CPU of a message moved between 590 and 770 µs from one run to the next in both,
with what else the machine was doing, so the 5 to 15 % is not a firm figure.

The wait in the SDK is not the same span in the two. Both stamp a message when the broker client hands it over, and
again after the decode: 0.0.58 decodes on the client's consumer thread right there, so its wait is the decode alone
and any queue of its own sits in the client's buffers, before the stamp (it acknowledges on delivery, so the broker
sees none); 1.0 hands the delivery to its session thread, with up to the prefetch of 200 waiting, so its wait is the
decode and that queue. The age, from the producer's stamp to the callback, is the same span for both: in the stretches
of a run where the machine was not disturbed its p99 was 52 to 53 ms for 0.0.58 (three stretches of 25 to 30 s) and
typically 50 to 60 ms for 1.0 (up to 114 ms in some), where the wait's p99 differs by 10 to 20 ms. So most of the gap in
the wait is where the stamp sits, not time 1.0 takes, and some of it is real: a few milliseconds of p99 at the most. The
harness reports the age split at the hand-over (`arrival`, before it, and `receipt to the callback`, after it) for both.

A message's age includes what the publisher, the broker and the machine do, and on a shared machine that moves. With
other builds keeping its load average above 5, ages of seconds came in spells in both lines (0.0.58 in every run, up to 14 s;
1.0 in some, up to 6 to 16 s, but with the backlog showing as a wait of up to 0.3 s in the SDK, since it holds at most
the prefetch and leaves the rest at the broker); 0.0.58's backlog sits before its stamp, in the client's buffers and the
connection, where the wait cannot see it, so its wait stayed at 4 to 5 ms (at most 156 ms) through them. Read the throughput and the
age of an undisturbed stretch first.

### Allocation against 0.0.58

1.0 first allocated 13 % more per message than 0.0.58 at this rate (317 to 318 KB against 281 KB). Flight recordings
of both, 4 minutes each at the same rate (`SYSTEMTESTS_LOAD_JFR=true`, read with `jfr print`), put the difference in one place: the decode.
Per message, 0.0.58 spends about 187 KB of its 281 on decoding and 63 KB on building the markets the callback reads,
and 30 KB on the broker client's frames; 1.0 spent 225 KB, 62 KB and 30 KB. Both decode with JAXB, 0.0.58 over the JDK's
XML parser and 1.0 over Woodstox, which is faster (see the decoder numbers above) and allocates a sixth to a fifth more
per message - the strings it keeps for each element's attribute values and the objects and tables it builds for each
document. That is the cost of the parser 1.0 chose, and the whole of the gap: 37 KB more in the decode, the rest equal.
The changes below cut the building of the markets to 40 KB, about a third less than 0.0.58's.

The changes - a market's specifiers held in an array rather than a linked hash map, a market's names making their map when a
name is first read, the feed's enums keeping their constants rather than copying them for every lookup - cut the entity
build's allocation by about 40 % (20 markets 16.6 to 10.4 KB, 150 markets 112.9 to 68.0 KB, 500 markets 383.7 to 229.6 KB) and a
message's by 21 KB, to 297 KB: 6 % above 0.0.58, the decode's 37 KB. What is left is the decoder, and is not cheap to
take: about 11 % of the allocation is JAXB's thread-local bookkeeping for each element, the same in both lines, and more
than a fifth the attribute values' strings, for which the alternative is the hand-written reader the decoder numbers
compare with, which costs a parser to keep in step with the schema.

## A slow listener slows only its own session

`SlowListenerTest` runs two sessions on one feed, a live-only one whose listener sleeps 50 ms for every message and a
prematch-only one whose listener does nothing, over the same traffic. The slow session falls behind by seconds and the
other does not notice: with 300 messages each, the slow session's messages waited up to 8 s in the SDK, with its queue
at 158 of the prefetch of 200 and the rest at the broker; the other session's waited at most 4 ms (p99 3 ms) with its queue
at 1, against 33 ms (p99 9 ms) with nothing slow - the first messages of a run, before the JIT. The budget is in
`listener-budgets.properties`: 250 ms and a queue of 20 for the other session, far above what a session that is not
blocked needs on a shared runner and far below the seconds a blocked one would wait. Needs Docker, like the cold
scenario, and takes about 35 s.
