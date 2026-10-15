package gg.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;

/**
 * The configuration every example runs with, read from the environment so that no token is ever
 * written into code:
 *
 * <ul>
 *   <li>{@code ODDIN_GG_ODDS_FEED_TOKEN}: your access token (required)
 *   <li>{@code ODDIN_GG_ODDS_FEED_ENVIRONMENT}: {@code integration} (default), {@code test} or
 *       {@code production}
 *   <li>{@code ODDIN_GG_ODDS_FEED_NODE_ID}: the node id, 1 by default; give every instance that runs
 *       at the same time its own
 * </ul>
 */
final class ExampleConfig {

    private ExampleConfig() {}

    static OddsFeedConfiguration fromEnvironment() {
        var token = System.getenv("ODDIN_GG_ODDS_FEED_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("set ODDIN_GG_ODDS_FEED_TOKEN to your access token");
        }
        var nodeId = Integer.parseInt(System.getenv().getOrDefault("ODDIN_GG_ODDS_FEED_NODE_ID", "1"));

        var builder = OddsFeed.getOddsFeedConfigurationBuilder();
        // An environment and a token are required: build() throws without either.
        var environment = System.getenv().getOrDefault("ODDIN_GG_ODDS_FEED_ENVIRONMENT", "integration");
        switch (environment.toLowerCase(java.util.Locale.ROOT)) {
            case "integration" -> builder.selectIntegration();
            case "test" -> builder.selectTest();
            case "production" -> builder.selectProduction();
            default -> throw new IllegalStateException("unknown environment: " + environment);
        }
        return builder.setAccessToken(token).setSDKNodeId(nodeId).build();
    }
}
