package com.oddin.oddsfeed.fakes;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A stand-in for the odds-feed REST API, answering from the vendored schema fixtures.
 *
 * <p>Every endpoint the SDK calls has a default answer from {@code vendor/oddsfeedschema}; the
 * same fixture comes back whatever id is asked for, so a test that cares about a particular
 * entity overrides that path with {@link #respond}, typically with a body built from
 * {@link Fixtures}. Anything without a route gets the API's 404. Requests other than GET -
 * recovery and replay control - are accepted with 202. Every request is recorded, in order, for
 * tests to check what the SDK asked for. A path can also be given replies in turn, each with its
 * own headers and delay, for a client that retries, waits or gives up.
 *
 * <p>It speaks HTTPS because the old SDK allows nothing else, with a certificate the test JVM
 * trusts (see {@link TestTls}).
 *
 * <p>Closing waits until requests stop arriving. The old SDK side-loads related entities in the
 * background after a call has returned, and it keeps its API address in one place for the whole
 * JVM, so a late request from one test would otherwise land on the next test's fake. Waiting for
 * quiet narrows that window but cannot close it - a pause in the SDK longer than the quiet period
 * still slips through - so assert on the requests you expect rather than on the exact list.
 */
public final class FakeRestServer implements AutoCloseable {

    /** Paths as the SDK builds them, after the host; the language segment is any language. */
    private static final List<Route> ROUTES = List.of(
            route("/v1/users/whoami", "rest/whoami/bookmaker_details.xml"),
            route("/v1/descriptions/producers", "rest/producers/producers.xml"),
            route("/v1/descriptions/void_reasons", "rest/void_reasons/void_reasons.xml"),
            route("/v1/descriptions/{lang}/markets", "rest/markets/market_descriptions.xml"),
            // the SDK reads variants into the same type as the plain list, so the same fixture fits
            route("/v1/descriptions/{lang}/markets/{id}/variants/{id}", "rest/markets/market_descriptions.xml"),
            route("/v1/descriptions/{lang}/match_status", "rest/match_status/match_status_descriptions.xml"),
            route("/v1/sports/{lang}/sports", "rest/sports/sports.xml"),
            route("/v1/sports/{lang}/sports/{id}/tournaments", "rest/sport_tournaments/sport_tournaments.xml"),
            route("/v1/sports/{lang}/tournaments/{id}/info", "rest/tournament_info/tournament_info.xml"),
            route("/v1/sports/{lang}/sport_events/{id}/summary", "rest/match_summary/match_summary.xml"),
            route("/v1/sports/{lang}/sport_events/{id}/fixture", "rest/fixtures_fixture/fixtures_fixture.xml"),
            route("/v1/sports/{lang}/fixtures/changes", "rest/fixture_changes/fixture_changes.xml"),
            route("/v1/sports/{lang}/schedules/{id}/schedule", "rest/schedule/schedule.xml"),
            route("/v1/sports/{lang}/competitors/{id}/profile", "rest/competitor/competitor_profile.xml"),
            route("/v1/sports/{lang}/players/{id}/profile", "rest/player/player_profile.xml"),
            route("/v1/replay", "rest/replay_content/replay_set_content.xml"));

    private static final Reply NOT_FOUND = Reply.of(404, Fixtures.read("rest/error/not_found.xml"));
    private static final Reply ACCEPTED = Reply.of(202, "");
    private static final String OUTAGE_BODY = """
      <?xml version="1.0" encoding="UTF-8"?>
      <response response_code="SERVICE_UNAVAILABLE">
          <action>outage</action>
          <message>the fake REST server is down</message>
      </response>
      """;

    private final HttpsServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Script> overrides = new ConcurrentHashMap<>();
    private volatile Reply outage;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();
    private volatile long lastFinishedAt = System.nanoTime();

    private FakeRestServer() throws IOException {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(TestTls.serverContext()));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeRestServer start() {
        try {
            return new FakeRestServer();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the fake REST server", e);
        }
    }

    /**
     * What to pass to the SDK as its API host. An IP rather than "localhost", which can resolve
     * to an IPv6 address the server is not listening on.
     */
    public String apiHost() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answer this exact path, for any method, with this status and body until told otherwise. */
    public void respond(String path, int status, String body) {
        respond(path, Reply.of(status, body));
    }

    /**
     * Answer this exact path, for any method, with these replies in turn, one per request; the last
     * one keeps answering after the others are used up.
     */
    public void respond(String path, Reply... inTurn) {
        if (inTurn.length == 0) {
            throw new IllegalArgumentException("no replies for " + path);
        }
        overrides.put(path, new Script(List.of(inTurn), new AtomicInteger()));
    }

    /**
     * Answers every request - any path, any method, overrides included - with this status until
     * {@link #endOutage}, the way the API looks to the SDK while it is down.
     */
    public void startOutage(int status) {
        outage = Reply.of(status, OUTAGE_BODY);
    }

    /** Back to the routes and overrides. */
    public void endOutage() {
        outage = null;
    }

    /**
     * Everything received so far, oldest first. It can include a late background request from an SDK
     * an earlier test used, so check for what you expect instead of comparing the whole list.
     */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** The most requests the fake was answering at the same time, since it started. */
    public int mostInFlight() {
        return mostInFlight.get();
    }

    /** The requests received so far with this method and path, oldest first. */
    public List<RecordedRequest> requests(String method, String path) {
        return requests.stream()
                .filter(request ->
                        request.method().equals(method) && request.path().equals(path))
                .toList();
    }

    /**
     * The first request with this method and path, waiting up to ten seconds for it; for calls the
     * SDK makes on its own, such as a recovery request after an alive.
     *
     * @throws AssertionError if it never arrives, naming the paths that did
     */
    public RecordedRequest awaitRequest(String method, String path) throws InterruptedException {
        return awaitRequests(method, path, 1).getFirst();
    }

    /**
     * The requests with this method and path once there are at least {@code count} of them,
     * waiting up to ten seconds; for a call the SDK repeats, such as a second recovery.
     *
     * @throws AssertionError if fewer arrive, naming the paths that did
     */
    public List<RecordedRequest> awaitRequests(String method, String path, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            List<RecordedRequest> matching = requests(method, path);
            if (matching.size() >= count) {
                return matching;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError((count == 1
                                ? "no " + method + " " + path + " within 10 s"
                                : matching.size() + " of " + count + " " + method + " " + path + " within 10 s")
                        + "; the SDK asked for "
                        + requests.stream()
                                .map(request -> request.method() + " " + request.path())
                                .toList());
            }
            Thread.sleep(50);
        }
    }

    @Override
    public void close() {
        awaitQuiet();
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * Waits until requests stop arriving. Call it before closing the SDK, so the background work a
     * test started finishes inside that test rather than being cut off by the shutdown.
     */
    public void awaitQuiet() {
        awaitQuiet(Duration.ofMillis(500), Duration.ofSeconds(5));
    }

    /**
     * Until no request is being answered and none has finished for {@code quiet}. Timed from the
     * last response rather than the last arrival: the SDK's next side-load follows its handling of
     * the previous answer, not the request.
     */
    private void awaitQuiet(Duration quiet, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            long pause = Duration.ofMillis(20).toNanos();
            if (inFlight.get() == 0) {
                long silent = System.nanoTime() - lastFinishedAt;
                if (silent >= quiet.toNanos()) {
                    return;
                }
                pause = quiet.toNanos() - silent;
            }
            if (System.nanoTime() + pause > deadline) {
                throw new IllegalStateException("the SDK was still calling the fake after " + limit
                        + "; its background work would spill into whatever runs next");
            }
            try {
                Thread.sleep(Duration.ofNanos(pause));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            requests.add(
                    new RecordedRequest(method, path, exchange.getRequestURI().getRawQuery(), headers(exchange)));

            Reply reply = answer(method, path);
            if (reply.delay().isPositive()) {
                try {
                    Thread.sleep(reply.delay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = reply.body().getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            reply.headers().forEach(exchange.getResponseHeaders()::set);
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length == 0) {
                return;
            }
            if (!reply.stall().isPositive() && !reply.cutOff()) {
                exchange.getResponseBody().write(body);
                return;
            }
            // the headers and half the body, then a pause or a dropped connection
            OutputStream out = exchange.getResponseBody();
            out.write(body, 0, body.length / 2);
            out.flush();
            if (reply.cutOff()) {
                throw new IOException("the fake cut the answer off");
            }
            try {
                Thread.sleep(reply.stall());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            out.write(body, body.length / 2, body.length - body.length / 2);
        } finally {
            // in this order, so whoever sees nothing in flight also sees when it finished
            lastFinishedAt = System.nanoTime();
            inFlight.decrementAndGet();
        }
    }

    private Reply answer(String method, String path) {
        Reply down = outage;
        if (down != null) {
            return down;
        }
        Script override = overrides.get(path);
        if (override != null) {
            return override.next();
        }
        if (!"GET".equals(method)) {
            return ACCEPTED;
        }
        for (Route route : ROUTES) {
            if (route.pattern().matcher(path).matches()) {
                return route.response();
            }
        }
        return NOT_FOUND;
    }

    private static Map<String, String> headers(HttpExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders()
                .forEach((name, values) ->
                        headers.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.getFirst()));
        return Map.copyOf(headers);
    }

    private static Route route(String template, String fixture) {
        String regex =
                Pattern.quote(template).replace("{lang}", "\\E[a-z]{2}\\Q").replace("{id}", "\\E[^/]+\\Q");
        return new Route(Pattern.compile(regex), Reply.of(200, Fixtures.read(fixture)));
    }

    /**
     * One answer: a status, a body, headers next to the XML content type, how long to wait before
     * sending it, and whether to stop halfway through the body - for a while, or for good by
     * dropping the connection.
     */
    public record Reply(
            int status, String body, Map<String, String> headers, Duration delay, Duration stall, boolean cutOff) {

        public Reply {
            headers = Map.copyOf(headers);
        }

        public static Reply of(int status, String body) {
            return new Reply(status, body, Map.of(), Duration.ZERO, Duration.ZERO, false);
        }

        /** The same reply with this header as well. */
        public Reply withHeader(String name, String value) {
            var more = new HashMap<>(headers);
            more.put(name, value);
            return new Reply(status, body, more, delay, stall, cutOff);
        }

        /** The same reply, sent this long after the request arrived. */
        public Reply after(Duration wait) {
            return new Reply(status, body, headers, wait, stall, cutOff);
        }

        /** The same reply, pausing this long after the headers and half the body. */
        public Reply stallingMidBody(Duration pause) {
            return new Reply(status, body, headers, delay, pause, cutOff);
        }

        /** The same reply, dropping the connection after the headers and half the body. */
        public Reply cutOffMidBody() {
            return new Reply(status, body, headers, delay, stall, true);
        }
    }

    private record Route(Pattern pattern, Reply response) {}

    /** Replies in turn; the last one repeats. */
    private record Script(List<Reply> replies, AtomicInteger taken) {
        Reply next() {
            return replies.get(Math.min(taken.getAndIncrement(), replies.size() - 1));
        }
    }
}
