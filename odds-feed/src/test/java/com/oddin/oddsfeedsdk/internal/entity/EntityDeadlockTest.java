package com.oddin.oddsfeedsdk.internal.entity;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Cold loads of caches that write each other, all at once, through the façades: the sport and
 * tournament pair of the 0.0.54 incident, a match with its competitors and their players, and a
 * match with its fixture and its live state. A latch lets every reader go at the same moment, and
 * the fake API answers each after the same pause, so the responses - and what they write into the
 * other cache - land together. Every reader must finish in time, and the JVM must see no deadlock
 * while they run. Caches and fetches run on platform threads here, which the JVM's deadlock check
 * sees; the time limit covers what it cannot.
 */
class EntityDeadlockTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final URN MATCH = URN.parse("od:match:198314");
    private static final URN COMPETITOR = URN.parse("od:competitor:47214");
    private static final URN PLAYER = URN.parse("od:player:9001");
    private static final URN TOURNAMENT = URN.parse("od:tournament:1042");
    private static final URN LOL = URN.parse("od:sport:1");
    private static final Duration PAUSE = Duration.ofMillis(50);
    private static final Duration LIMIT = Duration.ofSeconds(30);
    private static final int ROUNDS = 5;

    @Test
    void theSportAndTournamentCachesLoadedAtOnceDoNotDeadlock() throws Exception {
        try (var world = EntityWorld.onPlatformThreads(ExceptionHandlingStrategy.THROW)) {
            slow(world, "/v1/sports/{lang}/sports", "rest/sports/sports.xml");
            slow(
                    world,
                    "/v1/sports/{lang}/sports/od:sport:1/tournaments",
                    "rest/sport_tournaments/sport_tournaments.xml");
            slow(
                    world,
                    "/v1/sports/{lang}/tournaments/od:tournament:1042/info",
                    "rest/tournament_info/tournament_info.xml");
            slow(
                    world,
                    "/v1/sports/{lang}/tournaments/od:tournament:1043/info",
                    "rest/tournament_info/tournament_info.xml");
            race(
                    world,
                    () -> requireNonNull(world.entities
                                    .tournament(TOURNAMENT, null, List.of(EN, DE))
                                    .getSport())
                            .getNames(),
                    () -> {
                        for (Tournament tournament : requireNonNull(
                                world.entities.sport(LOL, List.of(EN, DE)).getTournaments())) {
                            tournament.getName(DE);
                        }
                    },
                    () -> world.sportsInfo.getActiveTournaments(EN),
                    () -> world.entities
                            .tournament(TOURNAMENT, LOL, List.of(DE))
                            .getCompetitors(),
                    () -> world.sportsInfo.getAvailableTournaments(LOL, DE));
        }
    }

    @Test
    void theMatchCompetitorAndPlayerCachesLoadedAtOnceDoNotDeadlock() throws Exception {
        try (var world = EntityWorld.onPlatformThreads(ExceptionHandlingStrategy.THROW)) {
            slow(
                    world,
                    "/v1/sports/{lang}/sport_events/od:match:198314/summary",
                    "rest/match_summary/match_summary.xml");
            slow(
                    world,
                    "/v1/sports/{lang}/competitors/od:competitor:47214/profile",
                    "rest/competitor/competitor_profile.xml");
            slow(
                    world,
                    "/v1/sports/{lang}/competitors/od:competitor:47215/profile",
                    "rest/competitor/competitor_profile.xml");
            slow(world, "/v1/sports/{lang}/players/od:player:9001/profile", "rest/player/player_profile.xml");
            race(
                    world,
                    () -> {
                        for (Competitor competitor : requireNonNull(
                                world.entities.match(MATCH, List.of(EN, DE)).getCompetitors())) {
                            for (Player player : requireNonNull(competitor.getPlayers())) {
                                requireNonNull(player).getNames();
                            }
                        }
                    },
                    () -> world.entities.competitor(COMPETITOR, List.of(DE, EN)).getPlayers(),
                    () -> world.entities.player(PLAYER, List.of(EN, DE)).getFullNames(),
                    () -> requireNonNull(
                                    world.entities.match(MATCH, List.of(DE)).getHomeCompetitor())
                            .getNames(),
                    () -> world.matches.preload(MATCH, List.of(EN, DE)));
        }
    }

    @Test
    void theMatchFixtureAndLiveStateLoadedAndWrittenAtOnceDoNotDeadlock() throws Exception {
        // THROW: a read that failed, or timed out waiting, must fail the round, not read as null
        try (var world = EntityWorld.onPlatformThreads(ExceptionHandlingStrategy.THROW)) {
            slow(
                    world,
                    "/v1/sports/{lang}/sport_events/od:match:198314/summary",
                    "rest/match_summary/match_summary.xml");
            slow(
                    world,
                    "/v1/sports/{lang}/sport_events/od:match:198314/fixture",
                    "rest/fixtures_fixture/fixtures_fixture.xml");
            var timestamps = new AtomicLong();
            // the summary in German, which the name reader always needs: the status reader may find the
            // live state the writer made and ask for no summary at all
            race(
                    world,
                    List.of(
                            "/v1/sports/de/sport_events/od:match:198314/summary",
                            "/v1/sports/en/sport_events/od:match:198314/fixture"),
                    gone(() -> requireNonNull(
                                    world.entities.match(MATCH, List.of(EN)).getFixture())
                            .getTvChannels()),
                    gone(() -> world.entities.match(MATCH, List.of(EN, DE)).getName(DE)),
                    gone(() -> requireNonNull(
                                    world.entities.match(MATCH, List.of(EN)).getStatus())
                            .getStatus()),
                    () -> {
                        for (int i = 0; i < 20; i++) {
                            var live = new OFSportEventStatus();
                            live.setStatus(OFEventStatus.LIVE);
                            world.matches.oddsChange(
                                    MATCH, 1, timestamps.incrementAndGet(), Duration.ZERO, world.time.instant(), live);
                        }
                    },
                    () -> world.matches.fixtureChange(MATCH));
            assertThat(timestamps.get()).isEqualTo(ROUNDS * 2 * 20);
        }
    }

    /**
     * The one outcome a reader may have here: the match found gone because a fixture change dropped
     * it between two of the reader's own reads, which is no deadlock. That is an {@link
     * ItemNotFoundException} with no cause; one that carries the API's failure - a load that failed or
     * a wait that timed out - and every other throwable fails the round.
     */
    private static Runnable gone(Runnable reader) {
        return () -> {
            try {
                reader.run();
            } catch (ItemNotFoundException e) {
                if (e.getCause() != null) {
                    throw e;
                }
            }
        };
    }

    /** Answers the path in every locale after {@link #PAUSE}, so that loads started together land together. */
    private static void slow(EntityWorld world, String path, String fixture) {
        for (String language : List.of("en", "de")) {
            world.api.respond(
                    path.replace("{lang}", language),
                    Reply.of(200, Fixtures.read(fixture)).after(PAUSE));
        }
    }

    /**
     * Each reader twice, on platform threads, all let go by one latch, for {@link #ROUNDS} rounds on
     * cold caches: every one finishes within {@link #LIMIT}, without failing, and the JVM sees no
     * deadlock meanwhile.
     */
    private static void race(EntityWorld world, Runnable... readers) throws InterruptedException {
        race(world, List.of(), readers);
    }

    /**
     * The same, and each round also loads every one of {@code loaded} at least once: a round starts
     * on cold caches with no load of the round before under way, so the first reader of each path
     * must ask the API for it. This is what shows the race reached the loads whose writes it is about,
     * not only that nothing failed.
     */
    private static void race(EntityWorld world, List<String> loaded, Runnable... readers) throws InterruptedException {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        for (int round = 0; round < ROUNDS; round++) {
            world.matches.clear();
            world.profiles.clear();
            var before = new ArrayList<Integer>();
            for (String path : loaded) {
                before.add(world.api.requests("GET", path).size());
            }
            var go = new CountDownLatch(1);
            var failures = new ConcurrentLinkedQueue<Throwable>();
            var running = new ArrayList<Thread>();
            for (Runnable reader : readers) {
                for (int copy = 0; copy < 2; copy++) {
                    running.add(Thread.ofPlatform()
                            .name("reader-" + round + "-" + running.size())
                            .start(() -> {
                                try {
                                    go.await();
                                    reader.run();
                                } catch (Throwable e) {
                                    failures.add(e);
                                }
                            }));
                }
            }
            go.countDown();
            long until = System.nanoTime() + LIMIT.toNanos();
            for (Thread reader : running) {
                while (reader.isAlive() && System.nanoTime() < until) {
                    long[] deadlocked = threads.findDeadlockedThreads();
                    assertThat(deadlocked)
                            .as(
                                    "deadlocked threads: %s",
                                    deadlocked == null
                                            ? ""
                                            : Arrays.toString(threads.getThreadInfo(deadlocked, true, true)))
                            .isNull();
                    reader.join(Duration.ofMillis(50));
                }
                assertThat(reader.isAlive())
                        .as("%s finished within %s", reader.getName(), LIMIT)
                        .isFalse();
            }
            assertThat(threads.findDeadlockedThreads()).isNull();
            assertThat(failures).as("round %d", round).isEmpty();
            if (!loaded.isEmpty()) {
                // every load of the round has ended, so none can be joined by the next round's reader
                world.api.awaitQuiet();
            }
            for (int i = 0; i < loaded.size(); i++) {
                assertThat(world.api.requests("GET", loaded.get(i)))
                        .as("round %d loaded %s", round, loaded.get(i))
                        .hasSizeGreaterThan(before.get(i));
            }
        }
        world.api.awaitQuiet();
    }
}
