package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.api.factories.OutcomeType;
import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The names of one market of one message, and of its outcomes, as 0.0.x made them. Nothing is read
 * until a name is asked for; then the market's description in that locale is read from the catalog
 * once, and every later name of the market in that locale reads it from here. A message's names
 * therefore read the catalog as it was when they were first asked for.
 *
 * <ul>
 *   <li>A market's name is its description's, with each {@code {specifier}} filled in with the
 *       market's value for it: {@code home} and {@code away} as the match's competitor's name, and in
 *       a {@code player_props} market a player id as the player's name.
 *   <li>An outcome's name is its description's; one the description does not list, of a market whose
 *       outcomes are players or competitors, is the player's or competitor's name. The outcome named
 *       {@code home} or {@code away} is the match's competitor's name.
 * </ul>
 *
 * <p>Safe for concurrent use.
 */
final class MarketNames {

    private static final String PLAYER_PROPS = "player_props";
    /** A {@code {specifier}} in a name's template; the innermost, when braces nest. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    private final Naming naming;
    private final int marketId;
    private final @Nullable String variant;
    private final Map<String, String> specifiers;
    private final SportEvent event;
    /** The market's description per locale once read, empty when the catalog does not have it. */
    private final Map<Locale, Optional<LocalizedMarket>> described = new ConcurrentHashMap<>();

    MarketNames(Naming naming, int marketId, Map<String, String> specifiers, SportEvent event) {
        this.naming = naming;
        this.marketId = marketId;
        this.variant = specifiers.get("variant");
        this.specifiers = specifiers;
        this.event = event;
    }

    Locale defaultLocale() {
        return naming.defaultLocale();
    }

    @Nullable
    String market(Locale locale) {
        return naming.name(
                () -> {
                    LocalizedMarket market = described(locale);
                    return market == null ? null : filledIn(market.name(), market.groups(), locale);
                },
                "market name",
                marketId);
    }

    @Nullable
    String outcome(String id, Locale locale) {
        return naming.name(
                () -> {
                    LocalizedMarket market = described(locale);
                    if (market == null) {
                        return null;
                    }
                    LocalizedMarket.Outcome outcome = market.outcome(id);
                    if (outcome != null) {
                        return competitorNamed(outcome.name(), locale);
                    }
                    OutcomeType type = market.type();
                    if (type == null) {
                        return null;
                    }
                    return switch (type) {
                        case PLAYER ->
                            naming.entities()
                                    .player(URN.parse(id), List.of(locale))
                                    .getName(locale);
                        case COMPETITOR ->
                            naming.entities()
                                    .competitor(URN.parse(id), List.of(locale))
                                    .getName(locale);
                    };
                },
                "outcome name",
                marketId + " " + id);
    }

    private @Nullable LocalizedMarket described(Locale locale) {
        Optional<LocalizedMarket> held = described.get(locale);
        if (held == null) {
            // read outside the map, so a fetch holds up no other locale's reader
            held = Optional.ofNullable(naming.catalog().market(marketId, variant, locale));
            // of two first readers, both keep the one stored, so every name reads the same description
            Optional<LocalizedMarket> first = described.putIfAbsent(locale, held);
            if (first != null) {
                held = first;
            }
        }
        return held.orElse(null);
    }

    /** The outcome named {@code home} or {@code away} is the match's competitor; null when it has none. */
    private @Nullable String competitorNamed(String name, Locale locale) {
        return switch (name) {
            case "home" -> competitorName(true, locale);
            case "away" -> competitorName(false, locale);
            default -> name;
        };
    }

    /**
     * The template with each {@code {specifier}} it names filled in, in one pass over the template:
     * what a value brings in is not filled in again. 0.0.x filled in one specifier after another, so a
     * value naming another specifier was filled in too, and a chain of them, each naming the next
     * twice, doubled the name at each link.
     */
    private String filledIn(String template, List<String> groups, Locale locale) {
        if (specifiers.isEmpty()) {
            return template;
        }
        boolean props = groups.contains(PLAYER_PROPS);
        var values = new HashMap<String, String>();
        return PLACEHOLDER.matcher(template).replaceAll(placeholder -> {
            String key = placeholder.group(1);
            String value = specifiers.get(key);
            if (value == null) {
                return Matcher.quoteReplacement(placeholder.group());
            }
            return Matcher.quoteReplacement(values.computeIfAbsent(key, _ -> filled(value, props, locale)));
        });
    }

    /** A specifier's value as a name shows it. */
    private String filled(String value, boolean props, Locale locale) {
        String filled = switch (value) {
            case "home" -> competitorName(true, locale);
            case "away" -> competitorName(false, locale);
            default -> value;
        };
        if (filled == null) {
            filled = value;
        }
        return props ? playerName(filled, locale) : filled;
    }

    private @Nullable String competitorName(boolean home, Locale locale) {
        if (!(event instanceof Match match)) {
            return null;
        }
        Competitor competitor = home ? match.getHomeCompetitor() : match.getAwayCompetitor();
        return competitor == null ? null : competitor.getName(locale);
    }

    /** A player id as the player's name, anything else as it is. */
    private String playerName(String value, Locale locale) {
        URN id;
        try {
            id = URN.parse(value);
        } catch (RuntimeException notAnId) {
            return value;
        }
        if (!URN.TypePlayer.equals(id.getType())) {
            return value;
        }
        String name = naming.entities().player(id, List.of(locale)).getName(locale);
        return name == null ? value : name;
    }
}
