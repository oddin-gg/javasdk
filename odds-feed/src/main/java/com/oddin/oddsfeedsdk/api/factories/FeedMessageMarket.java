package com.oddin.oddsfeedsdk.api.factories;

/** A market as a feed message carries it: implemented by the feed XML models. */
public interface FeedMessageMarket {
    int getId();

    String getSpecifiers();
}
