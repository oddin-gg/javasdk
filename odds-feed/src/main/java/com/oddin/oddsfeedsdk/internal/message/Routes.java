package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the ids out of a routing key, as 0.0.x read them: {@code
 * priority.prematch.live.type.sport.eventType.event[.node]}, a dash where there is none. A key with
 * neither a sport nor an event, or one of another shape, is a system key.
 */
public final class Routes {

    private static final Pattern ROUTE = Pattern.compile("\\A([^.]+)\\.([^.]+)\\.([^.]+)\\.([^.]+)"
            + "\\.(?<sportId>((\\d+)|(-)))\\.(?<eventType>((([a-z]+):([a-zA-Z_2]+))|(-)))\\.(?<eventId>((\\d+)|(-)))"
            + "(\\.(?<nodeId>((-?\\d+)|(-))))?(\\z)");
    private static final String SPORT_PREFIX = "od:sport:";
    private static final String NONE = "-";

    private Routes() {}

    public static RoutingKeyInfo parse(String route) {
        Matcher matcher = ROUTE.matcher(route);
        if (!matcher.find()) {
            return new RoutingKeyInfo(route, null, null, true);
        }
        String sport = matcher.group("sportId");
        String event = matcher.group("eventId");
        if (NONE.equals(sport) && NONE.equals(event)) {
            return new RoutingKeyInfo(route, null, null, true);
        }
        URN sportId = NONE.equals(sport) ? null : urn(SPORT_PREFIX + sport);
        String type = matcher.group("eventType");
        URN eventId = NONE.equals(type) || NONE.equals(event) ? null : urn(type + ":" + event);
        return new RoutingKeyInfo(route, sportId, eventId, false);
    }

    private static @Nullable URN urn(String id) {
        try {
            return URN.parse(id);
        } catch (RuntimeException notAnId) {
            return null;
        }
    }
}
