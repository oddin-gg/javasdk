package com.oddin.oddsfeedsdk.stress;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import java.time.Duration;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Faults for the fake REST API, decided by the seed and the request's ordinal alone - never by timing
 * or thread - so the same seed gives the same faults in the same order of requests. Every request
 * waits 0-150 ms; 5% of them are answered 503, and 1% are cut off or stall halfway through the body.
 *
 * <p>The calls the SDK makes while it opens are exempt and take no ordinal, so a run's fault sequence
 * starts the same whatever the start-up did. {@link #install} puts it on a fake; install it after
 * {@code open()} to leave the start alone as well.
 *
 * <p>A stalled answer is held until its pause is over or the fake's {@code awaitQuiet}/{@code close}
 * releases it, which they do: the SDK gives up on a stall at its own timeout, and what the fake still
 * holds then must not count as a request in flight.
 */
final class ChaosApi {

    /** What happens to one request. */
    enum Kind {
        NONE,
        SERVER_ERROR,
        CUT_OFF,
        STALL
    }

    record Fault(Duration latency, Kind kind) {}

    private static final Set<String> STARTUP =
            Set.of("/v1/users/whoami", "/v1/descriptions/producers", "/v1/descriptions/void_reasons");

    private static final String BODY = "<response>" + "x".repeat(64) + "</response>";

    private final long seed;
    private final Duration stall;
    private final AtomicLong ordinal = new AtomicLong();

    /**
     * @param stall how long a stalling answer pauses; make it longer than the SDK's HTTP timeout
     */
    ChaosApi(long seed, Duration stall) {
        this.seed = seed;
        this.stall = stall;
    }

    /** Answers the fake's requests through this policy. */
    void install(FakeRestServer api) {
        api.respondWith(this::reply);
    }

    /** The fault of this ordinal; a pure function of the seed. */
    Fault faultFor(long ordinal) {
        var random = new SplittableRandom(Seed.mix(seed ^ Seed.mix(ordinal)));
        var latency = Duration.ofMillis(random.nextInt(151));
        int roll = random.nextInt(100);
        var kind = roll < 5
                ? Kind.SERVER_ERROR
                : roll == 5 ? (random.nextBoolean() ? Kind.CUT_OFF : Kind.STALL) : Kind.NONE;
        return new Fault(latency, kind);
    }

    /** The fault for the next request, taking its ordinal; null for an exempt request. */
    Fault next(RecordedRequest request) {
        if (isStartup(request.path())) {
            return null;
        }
        return faultFor(ordinal.getAndIncrement());
    }

    /** The hook: waits out the latency, then answers the fault, or null to let the routes answer. */
    Reply reply(RecordedRequest request) {
        var fault = next(request);
        if (fault == null) {
            return null;
        }
        try {
            Thread.sleep(fault.latency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return replyTo(fault);
    }

    Reply replyTo(Fault fault) {
        return switch (fault.kind()) {
            case NONE -> null;
            case SERVER_ERROR -> Reply.of(503, "<response response_code=\"SERVICE_UNAVAILABLE\"/>");
            case CUT_OFF -> Reply.of(200, BODY).cutOffMidBody();
            case STALL -> Reply.of(200, BODY).stallingMidBody(stall);
        };
    }

    private static boolean isStartup(String path) {
        return STARTUP.contains(path)
                || path.matches("/v1/descriptions/[a-z]{2}/markets")
                || path.endsWith("/match_status");
    }
}
