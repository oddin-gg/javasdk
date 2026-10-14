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

2026-10-05, the same machine, the warm path's steps after the decode, per odds change:

| markets | cache write | entity build |
|--------:|------------:|-------------:|
| 20 | 0.4 µs, 2.5 KB | 1.7 µs, 16 KB |
| 150 | 0.4 µs, 2.5 KB | 12.8 µs, 112 KB |
| 500 | 0.4 µs, 2.5 KB | 50 µs, 380 KB |

Both are small next to the decode: a typical live odds change of 150 markets spends about 330 µs
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

Measured on this Linux box (Intel Pentium Gold 8505, JDK 25, Docker for the broker), median of 7 runs:

| | time |
|---|---:|
| caught up, 200 matches | 7.9 s (7.88 to 8.10 s), of which 6.4 s after the last publish |
| first message | 152 ms (71 to 161 ms) |

The 600 requests the 200 matches cost (a summary and two competitor profiles each) are answered
4 at a time, by the side-load workers the preload shares, so 50 ms each is 7.5 s: the pace is the
workers', not the broker's. With the preload off, the same 40 matches take 1.4 times as long, the
callbacks loading them one by one.

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

Measured on the same box, median of 7 runs, with the 200 matches:

| | variant mode |
|---|---:|
| caught up | 53.0 s (51.9 to 53.8 s), of which 49 to 52 s after the last publish |
| requests | 1606: the 606 above and 1000 variants, each once |
| most answered at once | 5 |
| longest callback | 0.31 to 0.37 s (5 variants and the plain names) |
| longest single name read | 58 to 104 ms |
| the feed's health, health events | healthy, none |

The pace is not the catalog's two permits. The callbacks of a session run one after another, and
each read of a cold variant is one request that the reader waits for, so the variants are fetched one
at a time: 1000 variants of 50 ms are 50 s, and what the preload does in parallel (the 606 requests,
done in 7.5 s) is hidden behind it. The time follows the number of variants and the latency, not the
matches: 50 matches took 2.2 s with no variant (only the plain names read), 13.6 s with 5 variants each (250 variants) and 52.7 s with 20
(1000 variants), a latency each and about 0.9 s besides; so 20 variants in each of 200 matches
would take about 200 s. No read was near the SDK's deadline for one request (30 s: the longest took
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
