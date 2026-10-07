package com.oddin.oddsfeedsdk.config;

import static java.util.Objects.requireNonNull;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The broker and API hosts of one environment. */
public final class Environment {
    private final String messagingHost;
    private final String apiHost;
    private final int messagingPort;

    public Environment(String messagingHost, String apiHost, int messagingPort) {
        this.messagingHost = requireNonNull(messagingHost, "messagingHost");
        this.apiHost = requireNonNull(apiHost, "apiHost");
        this.messagingPort = messagingPort;
    }

    public String getMessagingHost() {
        return messagingHost;
    }

    public String getApiHost() {
        return apiHost;
    }

    public int getMessagingPort() {
        return messagingPort;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Environment other
                && messagingHost.equals(other.messagingHost)
                && apiHost.equals(other.apiHost)
                && messagingPort == other.messagingPort;
    }

    @Override
    public int hashCode() {
        return Objects.hash(messagingHost, apiHost, messagingPort);
    }

    @Override
    public String toString() {
        return "Environment(messagingHost=" + messagingHost + ", apiHost=" + apiHost + ", messagingPort="
                + messagingPort + ")";
    }
}
