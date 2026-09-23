package com.oddin.oddsfeed.systemtests.fake;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.stream.Stream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * One self-signed certificate per test JVM, for the fake servers to present and for the SDK
 * under test to trust.
 *
 * <p>The old SDK hardcodes {@code https://} for the REST API, so the fake has to speak TLS. The
 * SDK makes its calls through the JVM defaults, and its HTTP client caches the socket factory on
 * first use for the whole JVM - hence one certificate, created once, rather than one per server.
 * The JDK's own trust stays in place next to it, so nothing else in the JVM loses HTTPS.
 */
final class TestTls {

  private static final char[] PASSWORD = "fake-rest".toCharArray();
  private static SSLContext serverContext;

  private TestTls() {}

  static synchronized SSLContext serverContext() {
    if (serverContext == null) {
      serverContext = create();
    }
    return serverContext;
  }

  private static SSLContext create() {
    try {
      KeyStore keyStore = generate();

      KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(keyStore, PASSWORD);
      SSLContext server = SSLContext.getInstance("TLS");
      server.init(keys.getKeyManagers(), null, null);

      trustInThisJvm(keyStore);
      return server;
    } catch (Exception e) {
      throw new IllegalStateException("could not set up TLS for the fake servers", e);
    }
  }

  /** keytool ships with every JDK and can write a SAN, which the JDK API cannot do without internals. */
  private static KeyStore generate() throws Exception {
    Path dir = Files.createTempDirectory("fake-rest-tls");
    Path file = dir.resolve("fake-rest.p12");
    try {
      Process keytool = new ProcessBuilder(
          Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
          "-genkeypair", "-alias", "fake-rest",
          "-keyalg", "RSA", "-keysize", "2048",
          "-dname", "CN=localhost",
          "-ext", "SAN=dns:localhost,ip:127.0.0.1",
          "-validity", "2",
          "-storetype", "PKCS12", "-keystore", file.toString(),
          "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
          .redirectErrorStream(true)
          .start();
      String output = new String(keytool.getInputStream().readAllBytes(), UTF_8);
      if (keytool.waitFor() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }

      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(file)) {
        keyStore.load(in, PASSWORD);
      }
      return keyStore;
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(dir);
    }
  }

  private static void trustInThisJvm(KeyStore ours) throws Exception {
    X509TrustManager fake = trustManager(ours);
    X509TrustManager jdk = trustManager(null);
    X509TrustManager both = new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        jdk.checkClientTrusted(chain, authType);
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        try {
          fake.checkServerTrusted(chain, authType);
        } catch (CertificateException notTheFake) {
          jdk.checkServerTrusted(chain, authType);
        }
      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return Stream.of(fake.getAcceptedIssuers(), jdk.getAcceptedIssuers())
            .flatMap(Stream::of)
            .toArray(X509Certificate[]::new);
      }
    };

    SSLContext client = SSLContext.getInstance("TLS");
    client.init(null, new TrustManager[] {both}, null);
    SSLContext.setDefault(client);
    HttpsURLConnection.setDefaultSSLSocketFactory(client.getSocketFactory());
  }

  /** A null key store means the JDK's own trusted certificates. */
  private static X509TrustManager trustManager(KeyStore keyStore) throws Exception {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(keyStore);
    for (TrustManager manager : factory.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509) {
        return x509;
      }
    }
    throw new IllegalStateException("no X509 trust manager available");
  }
}
