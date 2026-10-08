package com.oddin.oddsfeedsdk.internal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The eager preload reads the match a routing key names, and asks for it in the feed's locales. */
class MessagePreloadTest {

    private final List<String> asked = new ArrayList<>();

    @Test
    void aMatchsMessageAsksForTheMatchInEachLocale() {
        var preload = new MessagePreload((id, locales) -> asked.add(id + " " + locales), List.of(Locale.ENGLISH));
        preload.accept("hi.pre.-.odds_change.1.od:match.198314.-");
        preload.accept("lo.-.live.bet_settlement.2.od:match.7");
        assertThat(asked).containsExactly("od:match:198314 [en]", "od:match:7 [en]");
    }

    @Test
    void aTournamentsOrASystemMessageAsksForNothing() {
        var preload = new MessagePreload((id, locales) -> asked.add(id.toString()), List.of(Locale.ENGLISH));
        preload.accept("hi.pre.-.fixture_change.1.od:tournament.1.-");
        preload.accept("-.-.-.alive.-.-.-.-");
        preload.accept("-.-.-.snapshot_complete.-.-.-.7");
        preload.accept("not a routing key");
        assertThat(asked).isEmpty();
    }

    @Test
    void theLocalesAreTheDefaultFirstThenThePreloadLocalesEachOnce() {
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectProduction()
                .setAccessToken("token")
                .setDefaultLocale(Locale.GERMAN)
                .setPreloadLocales(List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH))
                .build();
        assertThat(MessagePreload.of((URN id, List<Locale> locales) -> {}, configuration)
                        .locales())
                .containsExactly(Locale.GERMAN, Locale.ENGLISH, Locale.FRENCH);
        assertThat(MessagePreload.of(
                                (id, locales) -> {},
                                OddsFeed.getOddsFeedConfigurationBuilder()
                                        .selectProduction()
                                        .setAccessToken("token")
                                        .build())
                        .locales())
                .containsExactly(Locale.ENGLISH);
    }
}
