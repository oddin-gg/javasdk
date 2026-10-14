package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeedsdk.stress.ChaosApi.Fault;
import com.oddin.oddsfeedsdk.stress.ChaosApi.Kind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The faults are a function of the seed and the request's ordinal. */
class ChaosApiTest {

    private static final Duration STALL = Duration.ofSeconds(30);
    private static final RecordedRequest SUMMARY =
            new RecordedRequest("GET", "/v1/sports/en/sport_events/od:match:1/summary", null, Map.of());

    @Test
    void theSameSeedGivesTheSameFaultsInTheSameOrder() {
        assertThat(run(7, 2000)).isEqualTo(run(7, 2000));
        assertThat(run(7, 2000)).isNotEqualTo(run(8, 2000));
    }

    @Test
    void theFaultsComeAtTheStatedRatesAndLatencies() {
        var faults = run(1, 20_000);

        assertThat(faults)
                .allSatisfy(fault -> assertThat(fault.latency()).isBetween(Duration.ZERO, Duration.ofMillis(150)));
        assertThat(count(faults, Kind.SERVER_ERROR)).isBetween(800L, 1200L);
        assertThat(count(faults, Kind.CUT_OFF) + count(faults, Kind.STALL)).isBetween(100L, 300L);
        assertThat(count(faults, Kind.CUT_OFF)).isPositive();
        assertThat(count(faults, Kind.STALL)).isPositive();
    }

    @Test
    void theCallsOfTheStartAreExemptAndTakeNoOrdinal() {
        var chaos = new ChaosApi(3, STALL);
        for (var path : List.of(
                "/v1/users/whoami",
                "/v1/descriptions/producers",
                "/v1/descriptions/void_reasons",
                "/v1/descriptions/en/markets",
                "/v1/descriptions/en/match_status")) {
            assertThat(chaos.next(new RecordedRequest("GET", path, null, Map.of())))
                    .isNull();
        }
        assertThat(chaos.next(SUMMARY)).isEqualTo(run(3, 1).getFirst());
    }

    @Test
    void eachKindIsAnsweredAsItsName() {
        var chaos = new ChaosApi(0, STALL);

        assertThat(chaos.replyTo(new Fault(Duration.ZERO, Kind.NONE))).isNull();
        assertThat(chaos.replyTo(new Fault(Duration.ZERO, Kind.SERVER_ERROR)).status())
                .isEqualTo(503);
        assertThat(chaos.replyTo(new Fault(Duration.ZERO, Kind.CUT_OFF)).cutOff())
                .isTrue();
        assertThat(chaos.replyTo(new Fault(Duration.ZERO, Kind.STALL)).stall()).isEqualTo(STALL);
    }

    private static List<Fault> run(long seed, int requests) {
        var chaos = new ChaosApi(seed, STALL);
        var faults = new ArrayList<Fault>();
        for (int i = 0; i < requests; i++) {
            faults.add(chaos.next(SUMMARY));
        }
        return faults;
    }

    private static long count(List<Fault> faults, Kind kind) {
        return faults.stream().filter(fault -> fault.kind() == kind).count();
    }
}
