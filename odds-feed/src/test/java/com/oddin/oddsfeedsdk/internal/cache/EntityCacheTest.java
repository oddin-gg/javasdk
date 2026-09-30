package com.oddin.oddsfeedsdk.internal.cache;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.internal.cache.EntityCache.Stamp;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The write rule, generations and bounds, on a competitor-like entity. */
class EntityCacheTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;

    private static final Field<String> NAME = Field.localized("name");
    private static final Field<String> COUNTRY = Field.localized("country");
    private static final Field<String> ABBREVIATION = Field.shared("abbreviation");
    private static final Field<String> ICON = Field.shared("icon path");
    private static final Field<List<String>> PLAYERS = Field.shared("players");

    /** Authoritative for everything but the icon; always sends the abbreviation and the players. */
    private static final Endpoint PROFILE = new Endpoint(
            "competitor profile", Set.of(NAME, COUNTRY, ABBREVIATION, PLAYERS), Set.of(ABBREVIATION, PLAYERS));
    /** Authoritative for the icon only; it also carries names. */
    private static final Endpoint ICONS = new Endpoint("icons", Set.of(ICON), Set.of());

    private static final Endpoint SCHEDULE = new Endpoint("schedule", Set.of(), Set.of());
    private static final Endpoint NAME_ONLY = new Endpoint("name only", Set.of(NAME), Set.of());

    private final FakeTime time = new FakeTime();
    private final EntityCache<String> cache = new EntityCache<>("competitor", 100, Duration.ofHours(24), time, time);

    @Test
    void anAuthoritativeResponseReplacesItsFieldsAndMarksTheLocaleLoaded() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of("p1", "p2"));
        Entry entry = entry("c1");
        assertThat(entry.get(NAME, EN)).isEqualTo("Team One");
        assertThat(entry.get(COUNTRY, EN)).isEqualTo("CZ");
        assertThat(entry.get(ABBREVIATION, null)).isEqualTo("T1");
        assertThat(entry.get(PLAYERS, null)).containsExactly("p1", "p2");
        assertThat(entry.loadedAt(PROFILE, EN)).isEqualTo(time.instant());
        assertThat(entry.loadedAt(PROFILE, DE)).isNull();
        assertThat(cache.isFresh("c1", PROFILE, EN)).isTrue();
        assertThat(cache.isFresh("c1", PROFILE, DE)).isFalse();

        profile("c1", EN, "Team One Renamed", "CZ", "T1", List.of("p1"));
        assertThat(entry("c1").get(NAME, EN)).isEqualTo("Team One Renamed");
        assertThat(entry("c1").get(PLAYERS, null)).containsExactly("p1");
    }

    @Test
    void aFieldTheAuthoritativeResponseOmitsIsClearedAndStaysMarked() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of("p1"));
        // the retracted country: a localized field the profile omits is gone
        profile("c1", EN, "Team One", null, "T1", List.of("p1"));
        Entry entry = entry("c1");
        assertThat(entry.get(COUNTRY, EN)).isNull();
        assertThat(entry.isAuthoritative(COUNTRY, EN)).isTrue();

        // and nothing another endpoint carries brings it back
        cache.fill("c1", Write.from(SCHEDULE, EN).put(COUNTRY, "SK"));
        assertThat(entry("c1").get(COUNTRY, EN)).isNull();
    }

    @Test
    void aSharedFieldIsClearedByOmissionOnlyWhenTheEndpointAlwaysSendsIt() {
        var alwaysSendsNothing = new Endpoint("profile, loosely", Set.of(NAME, ABBREVIATION), Set.of());
        cache.writeAuthoritative(
                "c1", Write.from(alwaysSendsNothing, EN).put(NAME, "Team").put(ABBREVIATION, "T"), stamp("c1"));
        cache.writeAuthoritative("c1", Write.from(alwaysSendsNothing, DE).put(NAME, "Mannschaft"), stamp("c1"));
        assertThat(entry("c1").get(ABBREVIATION, null))
                .as("not always sent: kept")
                .isEqualTo("T");

        profile("c1", DE, "Mannschaft", "CZ", null, List.of());
        assertThat(entry("c1").get(ABBREVIATION, null)).as("always sent: gone").isNull();
    }

    @Test
    void sharedFieldsAreWrittenByAnAuthoritativeResponseInAnyLocale() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of("p1"));
        profile("c1", DE, "Mannschaft Eins", "CZ", "T-1", List.of("p1", "p9"));
        Entry entry = entry("c1");
        assertThat(entry.get(NAME, EN)).isEqualTo("Team One");
        assertThat(entry.get(NAME, DE)).isEqualTo("Mannschaft Eins");
        assertThat(entry.get(ABBREVIATION, null)).as("last writer wins").isEqualTo("T-1");
        assertThat(entry.get(PLAYERS, null)).containsExactly("p1", "p9");
    }

    @Test
    void anotherEndpointOnlyFillsWhatIsAbsentAndNeverMarksALocaleLoaded() {
        cache.fill("c1", Write.from(SCHEDULE, EN).put(NAME, "From A Schedule").put(ICON, "icon.png"));
        Entry filled = entry("c1");
        assertThat(filled.get(NAME, EN)).isEqualTo("From A Schedule");
        assertThat(filled.loadedAt(PROFILE, EN))
                .as("a fill marks nothing loaded")
                .isNull();
        assertThat(cache.isFresh("c1", PROFILE, EN)).isFalse();

        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        cache.fill(
                "c1", Write.from(SCHEDULE, EN).put(NAME, "Stale Schedule Name").put(COUNTRY, "SK"));
        Entry entry = entry("c1");
        assertThat(entry.get(NAME, EN)).as("written by the profile").isEqualTo("Team One");
        assertThat(entry.get(COUNTRY, EN)).isEqualTo("CZ");
        assertThat(entry.get(ICON, null))
                .as("the profile is not authoritative for it")
                .isEqualTo("icon.png");

        // a fill in another locale adds that locale's name
        cache.fill("c1", Write.from(SCHEDULE, DE).put(NAME, "Mannschaft"));
        assertThat(entry("c1").get(NAME, DE)).isEqualTo("Mannschaft");
        assertThat(entry("c1").loadedAt(PROFILE, DE)).isNull();

        // its own authoritative endpoint replaces what a fill put, and a fill no longer can
        cache.writeAuthoritative("c1", Write.from(ICONS, EN).put(ICON, "new.png"), cache.stamp("c1"));
        cache.fill("c1", Write.from(SCHEDULE, EN).put(ICON, "old.png"));
        assertThat(entry("c1").get(ICON, null)).isEqualTo("new.png");
    }

    @Test
    void aFetchThatStartedBeforeAnInvalidationIsThrownAway() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        Stamp started = cache.stamp("c1");
        cache.invalidate("c1");

        Entry tombstone = entry("c1");
        assertThat(tombstone.generation()).isNotEqualTo(started.generation());
        assertThat(tombstone.get(NAME, EN)).isNull();
        assertThat(tombstone.loadedAt(PROFILE, EN)).isNull();

        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "Old Name"), started))
                .isFalse();
        assertThat(entry("c1").get(NAME, EN)).isNull();

        // the caller reads again and fetches afresh
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "New Name"), cache.stamp("c1")))
                .isTrue();
        assertThat(entry("c1").get(NAME, EN)).isEqualTo("New Name");
    }

    @Test
    void aFetchThatStartedOnNothingIsThrownAwayWhenTheEntryWasInvalidatedMeanwhile() {
        Stamp started = cache.stamp("c1");
        assertThat(started.present()).isFalse();
        cache.invalidate("c1");
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "Old"), started))
                .isFalse();

        Stamp dropped = cache.stamp("c2");
        profile("c2", EN, "Written Meanwhile", "CZ", "T", List.of());
        assertThat(cache.writeAuthoritative("c2", profileWrite(EN, "Also Fine"), dropped))
                .as("another write created it: same generation, applies")
                .isTrue();
    }

    @Test
    void anInvalidationStillCountsWhenItsTombstoneIsGone() {
        // a fetch that started on nothing, its key invalidated, the tombstone expired
        Stamp onNothing = cache.stamp("c1");
        cache.invalidate("c1");
        time.advance(Duration.ofHours(25));
        assertThat(cache.get("c1")).isNull();
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "Before The Change"), onNothing))
                .isFalse();

        // a fetch that started on an entry, which was invalidated, dropped, and made again by a fill
        profile("c2", EN, "Two", "CZ", "T2", List.of());
        Stamp onEntry = cache.stamp("c2");
        cache.invalidate("c2");
        time.advance(Duration.ofHours(25));
        cache.fill("c2", Write.from(SCHEDULE, EN).put(COUNTRY, "SK"));
        assertThat(cache.writeAuthoritative("c2", profileWrite(EN, "Before The Change"), onEntry))
                .as("a new entry is not the one the fetch started with")
                .isFalse();
    }

    @Test
    void eachAuthoritativeEndpointIsFreshOnItsOwn() {
        cache.writeAuthoritative("c1", Write.from(ICONS, EN).put(ICON, "icon.png"), cache.stamp("c1"));
        assertThat(cache.isFresh("c1", ICONS, EN)).isTrue();
        assertThat(cache.isFresh("c1", PROFILE, EN))
                .as("the profile was never fetched")
                .isFalse();
    }

    @Test
    void aFetchWhoseEntryWasDroppedMeanwhileIsThrownAway() {
        profile("c1", EN, "One", "CZ", "T1", List.of());
        Stamp started = cache.stamp("c1");
        time.advance(Duration.ofHours(25));
        assertThat(cache.get("c1")).as("expired").isNull();
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "One Again"), started))
                .isFalse();
    }

    @Test
    void eachLocaleIsFreshForTheAgeOfItsOwnFetch() {
        var matches = new EntityCache<String>("match", 100, Duration.ofHours(12), time, time);
        matches.writeAuthoritative("m1", profileWrite(EN, "Match"), matches.stamp("m1"));
        time.advance(Duration.ofHours(11));
        matches.writeAuthoritative("m1", profileWrite(DE, "Spiel"), matches.stamp("m1"));
        time.advance(Duration.ofHours(2));
        assertThat(matches.isFresh("m1", NAME_ONLY, EN))
                .as("fetched 13 hours ago")
                .isFalse();
        assertThat(matches.isFresh("m1", NAME_ONLY, DE))
                .as("fetched 2 hours ago")
                .isTrue();
        assertThat(requireNonNull(matches.get("m1")).get(NAME, EN))
                .as("still held until refetched")
                .isEqualTo("Match");
    }

    @Test
    void anEntryIsDroppedItsAgeAfterItsLastWriteAndTheCacheIsBounded() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        time.advance(Duration.ofHours(24).plusSeconds(1));
        assertThat(cache.get("c1")).isNull();

        var bounded = new EntityCache<String>("competitor", 3, Duration.ofHours(24), time, time);
        for (int i = 0; i < 10; i++) {
            bounded.fill("c" + i, Write.from(SCHEDULE, EN).put(NAME, "Team " + i));
        }
        assertThat(bounded.size()).isEqualTo(3);
    }

    @Test
    void aWriteThatChangesNothingDoesNotKeepAnEntryAlive() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        time.advance(Duration.ofHours(20));
        // a schedule that carries only what the profile wrote adds nothing, and so is no write
        cache.fill("c1", Write.from(SCHEDULE, EN).put(NAME, "Team One"));
        time.advance(Duration.ofHours(5));
        assertThat(cache.get("c1")).as("24 hours after its last change").isNull();
        cache.fill("c9", Write.from(SCHEDULE, EN));
        assertThat(cache.get("c9")).as("a fill of nothing leaves nothing").isNull();
    }

    @Test
    void clearLeavesATombstoneForEveryEntry() {
        profile("c1", EN, "One", "CZ", "T1", List.of());
        profile("c2", EN, "Two", "CZ", "T2", List.of());
        Stamp started = cache.stamp("c1");
        cache.clear();
        assertThat(entry("c1").get(NAME, EN)).isNull();
        assertThat(entry("c2").get(NAME, EN)).isNull();
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "One"), started))
                .isFalse();
    }

    @Test
    void aLocalizedFieldNeedsItsLocale() {
        assertThatThrownBy(() -> Write.from(SCHEDULE, null).put(NAME, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.writeAuthoritative("c1", Write.from(PROFILE, null), cache.stamp("c1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Endpoint("odd", Set.of(NAME), Set.of(ICON)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void profile(
            String key,
            Locale locale,
            String name,
            @Nullable String country,
            @Nullable String abbreviation,
            List<String> players) {
        Write write = Write.from(PROFILE, locale)
                .put(NAME, name)
                .put(COUNTRY, country)
                .put(ABBREVIATION, abbreviation)
                .put(PLAYERS, players);
        assertThat(cache.writeAuthoritative(key, write, cache.stamp(key))).isTrue();
    }

    private static Write profileWrite(Locale locale, String name) {
        return Write.from(new Endpoint("name only", Set.of(NAME), Set.of()), locale)
                .put(NAME, name);
    }

    private Stamp stamp(String key) {
        return cache.stamp(key);
    }

    private Entry entry(String key) {
        return requireNonNull(cache.get(key), key);
    }
}
