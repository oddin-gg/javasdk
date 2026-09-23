package com.oddin.oddsfeed.systemtests.fake;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A stand-in for the odds feed: a real RabbitMQ broker in a container, set up the way the SDK
 * expects the feed to be, and a publisher that sends feed messages into it.
 *
 * <p>The SDK connects over TLS to the virtual host {@code /oddinfeed/<bookmaker id>}, logs in with
 * the access token as user name and an empty password, and binds a queue of its own to the
 * {@value #EXCHANGE} topic exchange. The broker here has that virtual host - for bookmaker 53, the
 * one the REST fake's whoami answer names - and both the feed and the replay exchange. It cannot
 * be faked in-process: the old SDK opens a real AMQP connection.
 *
 * <p>RabbitMQ's own user store refuses empty passwords, so the broker asks this class instead,
 * through its HTTP auth backend. Any user name is let in, to the one virtual host only, and every
 * connection is recorded as a {@link Login}.
 *
 * <p>Messages go in through the broker's management API rather than an AMQP client, so the fake
 * adds nothing to the classpath the SDK under test runs with.
 *
 * <p>{@link #pause()} freezes the broker without closing anything, the way a network failure looks
 * to the SDK; the broker's heartbeat is two seconds, so the SDK notices within a few.
 */
public final class FakeFeed implements AutoCloseable {

  /** The exchange live messages are published to. */
  public static final String EXCHANGE = "oddinfeed";

  /** The exchange replay sessions bind to. Declared so they can, nothing publishes to it yet. */
  public static final String REPLAY_EXCHANGE = "oddinreplay";

  /** The bookmaker in the REST fake's whoami answer; the SDK derives the virtual host from it. */
  public static final int BOOKMAKER_ID = 53;

  /** Pinned by digest, like everything else the build pulls; the digest covers every platform. */
  private static final DockerImageName IMAGE = DockerImageName.parse("rabbitmq:4.3.6-management"
      + "@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95");
  private static final int AMQPS = 5671;
  private static final int MANAGEMENT = 15672;

  /** How the broker reaches a port on this machine; see {@link Testcontainers#exposeHostPorts}. */
  private static final String HOST_FROM_CONTAINER = "host.testcontainers.internal";

  /** The management API's own login, not a feed connection; kept out of {@link #logins()}. */
  private static final String PUBLISHER = "fake-feed-publisher";

  private static final Pattern ROOT_TIMESTAMP = Pattern.compile("(<[a-z_]+\\b[^>]*?\\btimestamp=\")\\d+(\")");

  private final String virtualHost = "/oddinfeed/" + BOOKMAKER_ID;
  private final List<Login> logins = new CopyOnWriteArrayList<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  // HTTP/1.1: the default first asks to upgrade to HTTP/2, and the management API hangs up on that
  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  private final HttpServer auth;
  private final GenericContainer<?> broker;
  private boolean paused;

  private FakeFeed() throws IOException {
    auth = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    auth.setExecutor(executor);
    auth.createContext("/", this::authorize);
    auth.start();
    Testcontainers.exposeHostPorts(auth.getAddress().getPort());

    TestTls.Pem tls = TestTls.pem();
    broker = new GenericContainer<>(IMAGE)
        .withAccessToHost(true)
        // Start as the image's own user rather than as root dropping to it. On some Docker setups
        // (colima, for one) the drop leaves the broker unable to read its Erlang cookie and it exits.
        .withCreateContainerCmdModifier(cmd -> cmd.withUser("rabbitmq"))
        .withCopyToContainer(Transferable.of(config()), "/etc/rabbitmq/conf.d/90-fake-feed.conf")
        .withCopyToContainer(Transferable.of(
            "[rabbitmq_management,rabbitmq_auth_backend_http]."), "/etc/rabbitmq/enabled_plugins")
        .withCopyToContainer(Transferable.of(definitions()), "/etc/rabbitmq/fake-feed-definitions.json")
        .withCopyToContainer(Transferable.of(tls.certificate()), "/etc/rabbitmq/tls/cert.pem")
        .withCopyToContainer(Transferable.of(tls.privateKey()), "/etc/rabbitmq/tls/key.pem")
        .withExposedPorts(AMQPS, MANAGEMENT)
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
            .withStartupTimeout(Duration.ofMinutes(2)));
    try {
      broker.start();
    } catch (RuntimeException e) {
      stopLocal();
      throw e;
    }
  }

  public static FakeFeed start() {
    try {
      return new FakeFeed();
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the fake feed", e);
    }
  }

  /** What to pass to the SDK as its messaging host. */
  public String host() {
    return broker.getHost();
  }

  /** What to pass to the SDK as its messaging port: the broker's TLS listener. */
  public int port() {
    return broker.getMappedPort(AMQPS);
  }

  public String virtualHost() {
    return virtualHost;
  }

  /** Every connection the broker accepted so far, oldest first. A reconnect adds another. */
  public List<Login> logins() {
    return List.copyOf(logins);
  }

  /**
   * Publishes a vendored feed fixture as the live feed would; see {@link #publish(String)}.
   *
   * @param name the path under the fixtures directory, e.g. {@code "feed/alive/alive.xml"}
   */
  public boolean publishFixture(String name) {
    return publish(Fixtures.read(name));
  }

  /**
   * Publishes a feed message as the live feed would: the routing key comes from the message
   * (see {@link #routingKey}) and its {@code timestamp} is set to now. The SDK judges producer
   * health by message age, so a fixture's fixed timestamp would read as a feed long behind.
   *
   * @return whether the broker routed it to at least one queue; false when no SDK is bound for it
   */
  public boolean publish(String message) {
    String now = Long.toString(System.currentTimeMillis());
    Matcher timestamp = ROOT_TIMESTAMP.matcher(message);
    String stamped = timestamp.find()
        ? message.substring(0, timestamp.start()) + timestamp.group(1) + now + timestamp.group(2)
            + message.substring(timestamp.end())
        : message;
    return publish(routingKey(stamped), stamped);
  }

  /**
   * Publishes the message exactly as given, with this routing key, to the {@value #EXCHANGE}
   * exchange. Fails while the broker is paused.
   *
   * @return whether the broker routed it to at least one queue
   */
  public boolean publish(String routingKey, String message) {
    String body = "{\"routing_key\":" + json(routingKey)
        + ",\"payload\":" + json(message)
        + ",\"payload_encoding\":\"string\""
        + ",\"properties\":{\"timestamp\":" + System.currentTimeMillis() / 1000
        + ",\"content_type\":\"application/xml\"}}";
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + broker.getHost() + ":"
            + broker.getMappedPort(MANAGEMENT) + "/api/exchanges/"
            + URLEncoder.encode(virtualHost, UTF_8) + "/" + EXCHANGE + "/publish"))
        .header("Authorization", "Basic "
            + Base64.getEncoder().encodeToString((PUBLISHER + ":" + PUBLISHER).getBytes(UTF_8)))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("the broker refused to publish to " + routingKey + ": "
            + response.statusCode() + " " + response.body());
      }
      return response.body().replace(" ", "").contains("\"routed\":true");
    } catch (IOException e) {
      throw new UncheckedIOException("could not publish to " + routingKey, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing to " + routingKey, e);
    }
  }

  /**
   * The routing key the feed sends this message with, in eight dot-separated parts: priority,
   * prematch, live, message type, sport id, event type, event id, node id. For example
   * {@code hi.-.live.odds_change.-.od:match.198314.-}.
   *
   * <p>Producer 1 is prematch and 2 live, as in the producers fixture; any other is neither.
   * Messages do not carry the sport, so that part is {@code -}, which the SDK accepts; the node id
   * is {@code -}, meaning any SDK node. Alive and snapshot complete are system messages, with
   * {@code -} everywhere but the type. A test that needs another key passes it to
   * {@link #publish(String, String)}.
   */
  public static String routingKey(String message) {
    try {
      XMLStreamReader xml = XMLInputFactory.newFactory().createXMLStreamReader(new StringReader(message));
      try {
        xml.nextTag();
        String type = xml.getLocalName();
        if (type.equals("alive") || type.equals("snapshot_complete")) {
          return "-.-.-." + type + ".-.-.-.-";
        }
        String scope = switch (String.valueOf(xml.getAttributeValue(null, "product"))) {
          case "1" -> "pre.-";
          case "2" -> "-.live";
          default -> "-.-";
        };
        String event = xml.getAttributeValue(null, "event_id");
        int split = event == null ? -1 : event.lastIndexOf(':');
        String eventPart = split < 0 ? "-.-" : event.substring(0, split) + "." + event.substring(split + 1);
        return "hi." + scope + "." + type + ".-." + eventPart + ".-";
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("not a feed message: " + message, e);
    }
  }

  /**
   * Freezes the broker: connections stay open but nothing moves, as when the network drops. The
   * SDK notices when heartbeats stop, within a few seconds.
   */
  public synchronized void pause() {
    broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
    paused = true;
  }

  /** Unfreezes the broker. The SDK reconnects on its own schedule; {@link #logins()} shows when. */
  public synchronized void resume() {
    broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
    paused = false;
  }

  @Override
  public synchronized void close() {
    try {
      if (paused) {
        resume();
      }
      broker.stop();
    } finally {
      stopLocal();
    }
  }

  /** What runs in this JVM: the auth server, its threads and the publisher's client. */
  private void stopLocal() {
    try {
      http.close();
    } finally {
      auth.stop(0);
      executor.shutdownNow();
    }
  }

  /**
   * One connection the broker accepted.
   *
   * @param username the user name it logged in with; for the SDK, the access token
   * @param virtualHost the virtual host it opened
   */
  public record Login(String username, String virtualHost) {}

  /**
   * Answers the broker's HTTP auth backend. It asks with GET and query parameters, and takes
   * "allow" (optionally followed by tags) or "deny" as the whole answer.
   */
  private void authorize(HttpExchange exchange) throws IOException {
    try (exchange) {
      Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
      String username = query.getOrDefault("username", "");
      String answer = switch (exchange.getRequestURI().getPath()) {
        // the management API only lets tagged users in
        case "/user" -> username.equals(PUBLISHER) ? "allow administrator" : "allow";
        case "/vhost" -> {
          if (!virtualHost.equals(query.get("vhost"))) {
            yield "deny";
          }
          if (!username.equals(PUBLISHER)) {
            logins.add(new Login(username, query.get("vhost")));
          }
          yield "allow";
        }
        case "/resource", "/topic" -> "allow";
        default -> "deny";
      };
      byte[] body = answer.getBytes(UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
  }

  private String config() {
    String auth = "http://" + HOST_FROM_CONTAINER + ":" + this.auth.getAddress().getPort();
    return String.join("\n",
        "listeners.ssl.default = " + AMQPS,
        "ssl_options.certfile = /etc/rabbitmq/tls/cert.pem",
        "ssl_options.keyfile = /etc/rabbitmq/tls/key.pem",
        "ssl_options.verify = verify_none",
        "ssl_options.fail_if_no_peer_cert = false",
        "auth_backends.1 = http",
        "auth_http.user_path = " + auth + "/user",
        "auth_http.vhost_path = " + auth + "/vhost",
        "auth_http.resource_path = " + auth + "/resource",
        "auth_http.topic_path = " + auth + "/topic",
        // the client default is a minute, far too slow for a paused broker to register in a test
        "heartbeat = 2",
        "load_definitions = /etc/rabbitmq/fake-feed-definitions.json",
        "");
  }

  private String definitions() {
    return """
        {
          "vhosts": [{"name": %1$s}],
          "exchanges": [
            {"name": %2$s, "vhost": %1$s, "type": "topic", "durable": true,
             "auto_delete": false, "internal": false, "arguments": {}},
            {"name": %3$s, "vhost": %1$s, "type": "topic", "durable": true,
             "auto_delete": false, "internal": false, "arguments": {}}
          ]
        }
        """.formatted(json(virtualHost), json(EXCHANGE), json(REPLAY_EXCHANGE));
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> parameters = new HashMap<>();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        int eq = pair.indexOf('=');
        String name = eq < 0 ? pair : pair.substring(0, eq);
        String value = eq < 0 ? "" : pair.substring(eq + 1);
        parameters.put(URLDecoder.decode(name, UTF_8), URLDecoder.decode(value, UTF_8));
      }
    }
    return parameters;
  }

  private static String json(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
