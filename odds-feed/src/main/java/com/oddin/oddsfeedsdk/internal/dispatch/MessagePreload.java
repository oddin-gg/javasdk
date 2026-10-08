package com.oddin.oddsfeedsdk.internal.dispatch;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.internal.message.Routes;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The eager entity preload: told the routing key of each delivery a session's queue takes, on the
 * broker client's consumer thread, it hands a load of the match the key names to the side-loads, so
 * the match is warm by the time the session's callback reads it. Here it only reads the key and
 * queues; the load runs on a side-load worker, never on the consumer thread or a session's, and
 * nothing waits for it: the delivery is in the session's queue already.
 */
public final class MessagePreload implements Consumer<String> {

    private static final String FIXTURE_CHANGE = "fixture_change";

    private final BiConsumer<URN, List<Locale>> preload;
    private final List<Locale> locales;

    /**
     * @param preload what queues the loads of a match in some locales without waiting: the match
     *     caches' preload
     * @param locales the locales the match is loaded in: the default first
     */
    public MessagePreload(BiConsumer<URN, List<Locale>> preload, List<Locale> locales) {
        this.preload = preload;
        this.locales = List.copyOf(locales);
    }

    /**
     * For the feed: the default locale, then each preload locale whose language is not in yet - the
     * API is asked by the language, so {@code en_US} and {@code en} would load the same twice.
     */
    public static MessagePreload of(BiConsumer<URN, List<Locale>> preload, OddsFeedConfiguration configuration) {
        var languages = new HashSet<String>();
        var locales = new ArrayList<Locale>();
        for (Locale locale : concat(configuration.getDefaultLocale(), configuration.getPreloadLocales())) {
            if (languages.add(locale.getLanguage())) {
                locales.add(locale);
            }
        }
        return new MessagePreload(preload, locales);
    }

    private static List<Locale> concat(Locale first, List<Locale> rest) {
        var all = new ArrayList<Locale>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return all;
    }

    /** The locales the match is loaded in; for a test. */
    List<Locale> locales() {
        return locales;
    }

    @Override
    public void accept(String routingKey) {
        if (FIXTURE_CHANGE.equals(messageType(routingKey))) {
            // the session invalidates the match before the callback, which would throw the load away
            return;
        }
        URN event = Routes.parse(routingKey).getEventId();
        // a tournament's message has nothing the match caches load; a system message names no event
        if (event != null && URN.TypeMatch.equals(event.getType())) {
            preload.accept(event, locales);
        }
    }

    /** The routing key's fourth part, the message type, or null for a key with fewer parts. */
    private static @Nullable String messageType(String routingKey) {
        int start = 0;
        for (int part = 0; part < 3; part++) {
            start = routingKey.indexOf('.', start) + 1;
            if (start == 0) {
                return null;
            }
        }
        int end = routingKey.indexOf('.', start);
        return end < 0 ? routingKey.substring(start) : routingKey.substring(start, end);
    }
}
