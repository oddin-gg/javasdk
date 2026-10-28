package com.oddin.oddsfeedsdk.internal.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD;

import com.oddin.oddsfeed.fakes.FakeH2Server;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeed;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A closed transport is let go: nothing the HTTP client keeps after the close leads back to it. */
class RestTransportReleaseTest {

    /**
     * The JDK's client keeps an HTTP/2 connection's idle timeout, and with it the handler of the
     * exchange that last used the connection, after {@code shutdownNow}. The feed hangs on the
     * transport, so a handler that held it kept every closed feed alive.
     */
    @Test
    @Timeout(value = 120, threadMode = SEPARATE_THREAD)
    void aTransportThatMadeACallOverHttp2IsCollectedOnceItIsClosed() throws InterruptedException {
        try (var api = FakeH2Server.start()) {
            var queue = new ReferenceQueue<RestTransport>();
            var released = callAndClose(api, queue);

            assertThat(api.negotiatedProtocols())
                    .as("the call went over HTTP/2")
                    .containsExactly("h2");
            var give = Instant.now().plus(Duration.ofSeconds(60));
            while (Instant.now().isBefore(give) && queue.remove(100) == null) {
                System.gc();
            }
            assertThat(released.refersTo(null))
                    .as("the closed transport is collected")
                    .isTrue();
        }
    }

    /** In a method of its own, so that nothing here holds the transport when the caller looks for it. */
    private static WeakReference<RestTransport> callAndClose(FakeH2Server api, ReferenceQueue<RestTransport> queue) {
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setSDKNodeId(7)
                .build();
        var transport = new RestTransport(configuration, ApiEvents.NONE, TestTls.clientContext());
        transport.send("GET", Pool.CATALOG, "/users/whoami", true, transport.deadline());
        transport.close();
        return new WeakReference<>(transport, queue);
    }
}
