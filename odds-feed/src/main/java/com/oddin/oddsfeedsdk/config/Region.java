package com.oddin.oddsfeedsdk.config;

/** The region whose hosts the feed connects to. */
public enum Region {
    DEFAULT(""),
    AP_SOUTHEAST_1("ap-southeast-1.");

    private final String host;

    Region(String host) {
        this.host = host;
    }

    /** The region's part of a host name, with its trailing dot; empty for the default region. */
    public String getHost() {
        return host;
    }
}
