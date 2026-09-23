package com.oddin.oddsfeed.systemtests.fake;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
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
 * tests to check what the SDK asked for.
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

  private static final Response NOT_FOUND = new Response(404, Fixtures.read("rest/error/not_found.xml"));
  private static final Response ACCEPTED = new Response(202, "");

  private final HttpsServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Response> overrides = new ConcurrentHashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger();
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
    overrides.put(path, new Response(status, body));
  }

  /**
   * Everything received so far, oldest first. It can include a late background request from an SDK
   * an earlier test used, so check for what you expect instead of comparing the whole list.
   */
  public List<RecordedRequest> requests() {
    return List.copyOf(requests);
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
    inFlight.incrementAndGet();
    try (exchange) {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      requests.add(new RecordedRequest(method, path, exchange.getRequestURI().getRawQuery(), headers(exchange)));

      Response response = answer(method, path);
      byte[] body = response.body().getBytes(UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/xml");
      exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
      if (body.length > 0) {
        exchange.getResponseBody().write(body);
      }
    } finally {
      // in this order, so whoever sees nothing in flight also sees when it finished
      lastFinishedAt = System.nanoTime();
      inFlight.decrementAndGet();
    }
  }

  private Response answer(String method, String path) {
    Response override = overrides.get(path);
    if (override != null) {
      return override;
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
    exchange.getRequestHeaders().forEach((name, values) ->
        headers.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.getFirst()));
    return Map.copyOf(headers);
  }

  private static Route route(String template, String fixture) {
    String regex = Pattern.quote(template)
        .replace("{lang}", "\\E[a-z]{2}\\Q")
        .replace("{id}", "\\E[^/]+\\Q");
    return new Route(Pattern.compile(regex), new Response(200, Fixtures.read(fixture)));
  }

  private record Route(Pattern pattern, Response response) {}

  private record Response(int status, String body) {}
}
