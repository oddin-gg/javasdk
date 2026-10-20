# The 0.0.x examples

These are the examples of the 0.0.x line (Java 8, `com.oddin.oddsfeed:odds-feed`): `Main` and
`Replay` in `src/main/java/com/oddin/oddsfeed/examples`.

They stay here so that a test of the 1.0 line (`ApiUsageTest` in `odds-feed`) compiles them against
1.0, which shows that client code written for 0.0.x still compiles. This folder has no build of its
own.

To run them, check out the `release/0.x` branch: its `examples` directory has the same sources with
their Gradle build (Java 8, `com.oddin.oddsfeed:odds-feed` from the GitHub packages registry or your
local Maven repository), and its `library` directory the SDK they use. On that branch:

- `./library/gradlew clean publishToMavenLocal -p library`
- `cd examples && ./gradlew clean build`
- write your token into the environment property `ODDIN_GG_ODDS_FEED_TOKEN`
- `./gradlew run` (in `examples`)
