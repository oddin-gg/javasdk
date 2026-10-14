package com.oddin.oddsfeed.systemtests.load;

import com.oddin.oddsfeed.benchmarks.Catalogs;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The fake REST API of a load run: the generated catalogs and the per-match answers, each after a
 * fixed latency, the way the cold scenario of the benchmarks module answers; what the catalogs do
 * not generate is the fixtures', undelayed. The recovery requests, which are not GETs, get the
 * fake's own 202.
 */
final class LoadApi {

    static final Pattern SUMMARY = Pattern.compile("/v1/sports/en/sport_events/od:match:(\\d+)/summary");
    static final Pattern PROFILE = Pattern.compile("/v1/sports/en/competitors/od:competitor:(\\d+)/profile");
    static final Pattern VARIANT = Pattern.compile("/v1/descriptions/en/markets/(\\d+)/variants/([^/]+)");

    private LoadApi() {}

    /** The hook to hand {@code FakeRestServer.respondWith}; safe on the request threads. */
    static Function<RecordedRequest, Reply> answers(Duration latency) {
        var fixed = Map.of(
                "/v1/users/whoami", Fixtures.read("rest/whoami/bookmaker_details.xml"),
                "/v1/descriptions/producers", Fixtures.read("rest/producers/producers.xml"),
                "/v1/descriptions/void_reasons", Fixtures.read("rest/void_reasons/void_reasons.xml"),
                "/v1/descriptions/en/match_status", Fixtures.read("rest/match_status/match_status_descriptions.xml"),
                "/v1/descriptions/en/markets", text(Catalogs.marketDescriptions(Catalogs.MARKET_DESCRIPTIONS)),
                "/v1/sports/en/sports", text(Catalogs.sports(Catalogs.SPORTS)));
        return request -> {
            if (!request.method().equals("GET")) {
                return null;
            }
            var path = request.path();
            var body = fixed.get(path);
            if (body == null) {
                var summary = SUMMARY.matcher(path);
                var profile = PROFILE.matcher(path);
                var variant = VARIANT.matcher(path);
                if (summary.matches()) {
                    body = text(Catalogs.matchSummary(Long.parseLong(summary.group(1))));
                } else if (profile.matches()) {
                    body = text(Catalogs.competitorProfile(Long.parseLong(profile.group(1))));
                } else if (variant.matches()) {
                    body = text(Catalogs.variantDescription(
                            Integer.parseInt(variant.group(1)),
                            URLDecoder.decode(variant.group(2), StandardCharsets.UTF_8)));
                } else {
                    return null;
                }
            }
            return Reply.of(200, body).after(latency);
        };
    }

    private static String text(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8);
    }
}
