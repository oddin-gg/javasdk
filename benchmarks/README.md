# Benchmarks

JMH benchmarks of the SDK's hot path, with a budget per message that `./mvnw verify` checks.

## What runs

- `DecodeBenchmark` decodes one odds change, warm, three ways: the SDK's decoder (JAXB on
  Woodstox), JAXB on the JDK's own parser - the decoder as it was until 2026-10-01 - and a
  hand-written StAX reader into the same generated classes, on the JDK's parser. Each over
  three sizes: 20, 150 and 500 markets.
- The corpus is generated (`Corpus`): odds changes shaped like a live match's, with a status,
  period scores, a scoreboard and markets of two to twenty outcomes, three in four with
  specifiers. It is the same every time for a size, and `CorpusTest` checks it against the
  feed schema. There is no recorded traffic in the repository.
- `StaxOddsChangeReaderTest` holds the StAX reader to decoding exactly what JAXB decodes,
  over the corpus and every vendored odds change fixture, so the comparison compares the
  same work.
- `BudgetTest` runs the SDK's decoder and fails when it goes over `budgets.properties`:
  allocation per message within 1.5 times the measurement, time within 5 times. Allocation
  is the tight check, since it hardly moves between machines; time only catches a decoder
  that became several times slower. A slip back to the JDK's parser stays within both, so
  `XmlReaderTest` in `odds-feed` holds the decoder to Woodstox. JMH forks its own JVM, so the build's coverage agent
  does not run inside the measurement.

Only the SDK's decoder has a budget, so the build runs only it. For the comparison:

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
fast and within an eighth of the hand-written reader, with its name limit and per-document name
table counted in, which is why the decoder stays on JAXB and
the generated classes, with no parsing code to keep in step with the schema. It allocates
somewhat more. On 2026-09-29, before the change, the SDK's column read as the middle one;
keeping one unmarshaller instead of one per message changed nothing.

## Not here yet

The cold scenario - a restart where every entity is a miss, against the fake API with
realistic latency - needs the caches and the HTTP client, and joins when they exist. So do
the cache write and entity build steps of the warm path.
