# Benchmarks

JMH benchmarks of the SDK's hot path, with a budget per message that `./mvnw verify` checks.

## What runs

- `DecodeBenchmark` decodes one odds change, warm, three ways: the SDK's decoder (JAXB),
  JAXB with one unmarshaller kept instead of one per message, and a hand-written StAX
  reader into the same generated classes. Each over three sizes: 20, 150 and 500 markets.
- The corpus is generated (`Corpus`): odds changes shaped like a live match's, with a status,
  period scores, a scoreboard and markets of two to twenty outcomes, about half with
  specifiers. It is the same every time for a size, and `CorpusTest` checks it against the
  feed schema. There is no recorded traffic in the repository.
- `StaxOddsChangeReaderTest` holds the StAX reader to decoding exactly what JAXB decodes,
  over the corpus and every vendored odds change fixture, so the comparison compares the
  same work.
- `BudgetTest` runs the SDK's decoder and fails when it goes over `budgets.properties`:
  allocation per message within 1.5 times the measurement, time within 5 times. Allocation
  is the tight check, since it hardly moves between machines; time only catches a decoder
  that became several times slower. JMH forks its own JVM, so the build's coverage agent
  does not run inside the measurement.

Only the SDK's decoder has a budget, so the build runs only it. For the comparison:

```
./mvnw -pl odds-feed,benchmarks -am test -Dtest=BudgetTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbenchmarks.include=DecodeBenchmark -Dbenchmarks.iterations=10
```

## Numbers

2026-09-29, Apple M-series, JDK 25, per odds change, warm:

| markets | JAXB (the SDK) | JAXB, kept unmarshaller | StAX |
|--------:|---------------:|------------------------:|-----:|
| 20 | 155 µs, 82 KB | 156 µs, 81 KB | 51 µs, 91 KB |
| 150 | 900 µs, 396 KB | 917 µs, 396 KB | 296 µs, 330 KB |
| 500 | 3.1 ms, 1.29 MB | 3.0 ms, 1.29 MB | 1.0-1.3 ms, 1.02 MB |

Keeping the unmarshaller changes nothing: the cost is JAXB's reading, not its setup. StAX is
about three times faster at every size and allocates somewhat less. At the feed's usual rate,
about 40 messages a second, JAXB takes a few percent of a core; a recovery burst of thousands
of odds changes is where the difference shows.

## Not here yet

The cold scenario - a restart where every entity is a miss, against the fake API with
realistic latency - needs the caches and the HTTP client, and joins when they exist. So do
the cache write and entity build steps of the warm path.
