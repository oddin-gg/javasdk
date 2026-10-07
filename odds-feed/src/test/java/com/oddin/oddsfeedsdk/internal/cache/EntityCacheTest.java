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
    private static final Field<String> FULL_NAME = Field.localized("full name");
    private static final Field<String> ABBREVIATION = Field.shared("abbreviation");
    private static final Field<String> ICON = Field.shared("icon path");
    private static final Field<List<String>> PLAYERS = Field.shared("players");

    private static final Duration LONGEST_FETCH = Duration.ofMinutes(1);

    /** Authoritative for everything but the icon; sends all of it when it exists, but the full name. */
    private static final Endpoint PROFILE = new Endpoint(
            "competitor profile",
            Set.of(NAME, COUNTRY, FULL_NAME, ABBREVIATION, PLAYERS),
            Set.of(NAME, COUNTRY, ABBREVIATION, PLAYERS));
    /** Authoritative for the icon only; it also carries names. */
    private static final Endpoint ICONS = new Endpoint("icons", Set.of(ICON), Set.of());

    private static final Endpoint SCHEDULE = new Endpoint("schedule", Set.of(), Set.of());
    private static final Endpoint NAME_ONLY = new Endpoint("name only", Set.of(NAME), Set.of());

    private final FakeTime time = new FakeTime();
    private final EntityCache<String> cache =
            new EntityCache<>("competitor", 100, Duration.ofHours(24), LONGEST_FETCH, time, time);

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
        fill("c1", Write.from(SCHEDULE, EN).put(COUNTRY, "SK"));
        assertThat(entry("c1").get(COUNTRY, EN)).isNull();
    }

    @Test
    void aLocalizedFieldTheEndpointMayLeaveOutIsKeptWhenItDoes() {
        cache.writeAuthoritative(
                "c1", Write.from(PROFILE, EN).put(NAME, "Team One").put(FULL_NAME, "Team One FC"), stamp("c1"));
        cache.writeAuthoritative("c1", Write.from(PROFILE, EN).put(NAME, "Team One"), stamp("c1"));
        assertThat(entry("c1").isAuthoritative(FULL_NAME, EN)).isTrue();
        assertThat(entry("c1").get(FULL_NAME, EN)).as("left out, not gone").isEqualTo("Team One FC");

        cache.writeAuthoritative("c1", Write.from(PROFILE, DE).put(NAME, "Mannschaft Eins"), stamp("c1"));
        assertThat(entry("c1").isAuthoritative(FULL_NAME, DE))
                .as("nothing said of it in German")
                .isFalse();
        fill("c1", Write.from(SCHEDULE, DE).put(FULL_NAME, "Mannschaft Eins FC"));
        assertThat(entry("c1").get(FULL_NAME, DE)).as("so a fill may add it").isEqualTo("Mannschaft Eins FC");
    }

    @Test
    void anOlderFetchOfAnotherLocaleWritesItsLocaleButLeavesTheNewerSharedFields() {
        Stamp english = cache.stamp("c1");
        Stamp german = cache.stamp("c1");
        cache.writeAuthoritative(
                "c1",
                Write.from(PROFILE, DE)
                        .put(NAME, "Mannschaft")
                        .put(ABBREVIATION, "NEW")
                        .put(PLAYERS, List.of("p9")),
                german);
        assertThat(cache.isNewest("c1", PROFILE, english))
                .as("a later fetch wrote since")
                .isFalse();
        assertThat(cache.isNewest("c1", PROFILE, german)).isTrue();
        cache.writeAuthoritative("c1", Write.from(PROFILE, EN).put(NAME, "Team").put(ABBREVIATION, "OLD"), english);
        Entry entry = entry("c1");
        assertThat(entry.get(NAME, EN)).as("its own locale").isEqualTo("Team");
        assertThat(entry.get(ABBREVIATION, null)).as("the later fetch's").isEqualTo("NEW");
        assertThat(entry.get(PLAYERS, null))
                .as("always sent, and left out by the older fetch, but not its to clear")
                .containsExactly("p9");
        assertThat(cache.isFresh("c1", PROFILE, EN)).isTrue();
    }

    @Test
    void responsesOfTwoEndpointsOfOneEntityDoNotHoldEachOtherBack() {
        Stamp icons = cache.stamp("c1");
        Stamp profile = cache.stamp("c1");
        profile("c1", EN, "Team One", "CZ", "T1", List.of("p1"));
        assertThat(cache.writeAuthoritative("c1", Write.from(ICONS, EN).put(ICON, "icon.png"), icons))
                .isTrue();
        assertThat(entry("c1").get(ICON, null))
                .as("the icons' own shared field, though a later profile fetch wrote first")
                .isEqualTo("icon.png");
        assertThat(cache.isNewest("c1", ICONS, icons)).isTrue();
        assertThat(cache.isNewest("c1", PROFILE, icons))
                .as("the profile's was written by a later fetch")
                .isFalse();
        assertThat(profile.fetch()).isGreaterThan(icons.fetch());
    }

    @Test
    void aFieldTheResponseSaysNothingOfIsKeptThoughItsEndpointAlwaysSendsIt() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of("p1"));
        cache.writeAuthoritative(
                "c1",
                Write.from(PROFILE, EN)
                        .put(NAME, "Team One")
                        .put(COUNTRY, "CZ")
                        .put(PLAYERS, List.of("p1"))
                        .unsaid(ABBREVIATION),
                stamp("c1"));
        assertThat(entry("c1").get(ABBREVIATION, null)).isEqualTo("T1");
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
        fill("c1", Write.from(SCHEDULE, EN).put(NAME, "From A Schedule").put(ICON, "icon.png"));
        Entry filled = entry("c1");
        assertThat(filled.get(NAME, EN)).isEqualTo("From A Schedule");
        assertThat(filled.loadedAt(PROFILE, EN))
                .as("a fill marks nothing loaded")
                .isNull();
        assertThat(cache.isFresh("c1", PROFILE, EN)).isFalse();

        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        fill("c1", Write.from(SCHEDULE, EN).put(NAME, "Stale Schedule Name").put(COUNTRY, "SK"));
        Entry entry = entry("c1");
        assertThat(entry.get(NAME, EN)).as("written by the profile").isEqualTo("Team One");
        assertThat(entry.get(COUNTRY, EN)).isEqualTo("CZ");
        assertThat(entry.get(ICON, null))
                .as("the profile is not authoritative for it")
                .isEqualTo("icon.png");

        // a fill in another locale adds that locale's name
        fill("c1", Write.from(SCHEDULE, DE).put(NAME, "Mannschaft"));
        assertThat(entry("c1").get(NAME, DE)).isEqualTo("Mannschaft");
        assertThat(entry("c1").loadedAt(PROFILE, DE)).isNull();

        // its own authoritative endpoint replaces what a fill put, and a fill no longer can
        cache.writeAuthoritative("c1", Write.from(ICONS, EN).put(ICON, "new.png"), cache.stamp("c1"));
        fill("c1", Write.from(SCHEDULE, EN).put(ICON, "old.png"));
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
        assertThat(cache.discarded()).as("the stale fetch, counted").isEqualTo(1);

        // the caller reads again and fetches afresh
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "New Name"), cache.stamp("c1")))
                .isTrue();
        assertThat(entry("c1").get(NAME, EN)).isEqualTo("New Name");
        assertThat(cache.discarded()).as("a write is no discard").isEqualTo(1);
    }

    @Test
    void aFillOfAnInvalidatedEntryOrOfAnAbandonedFetchIsCountedAsDiscarded() {
        Stamp started = cache.stamp("c1");
        cache.invalidate("c1");
        assertThat(cache.fill("c1", Write.from(SCHEDULE, EN).put(NAME, "Old"), started))
                .isFalse();
        assertThat(cache.discarded()).isEqualTo(1);

        assertThat(cache.fill("c2", Write.from(SCHEDULE, EN).put(NAME, "Given Up"), cache.stamp("c2", () -> true)))
                .isFalse();
        assertThat(cache.writeAuthoritative("c2", profileWrite(EN, "Given Up"), cache.stamp("c2", () -> true)))
                .isFalse();
        assertThat(cache.discarded()).isEqualTo(3);

        assertThat(cache.fill("c2", Write.from(SCHEDULE, EN).put(NAME, "Fine"), cache.stamp("c2")))
                .isTrue();
        assertThat(cache.discarded()).as("a fill is no discard").isEqualTo(3);
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
    void anInvalidationCountsForAFetchThatStartedOnNoEntry() {
        Stamp onNothing = cache.stamp("c1");
        cache.invalidate("c1");
        assertThat(cache.get("c1"))
                .as("no tombstone for a key without an entry")
                .isNull();
        assertThat(cache.size()).isZero();
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "Before The Change"), onNothing))
                .isFalse();

        Stamp onOther = cache.stamp("c3");
        cache.invalidate("c4");
        assertThat(cache.writeAuthoritative("c3", profileWrite(EN, "Unrelated"), onOther))
                .as("another key's invalidation says nothing about this one")
                .isTrue();
    }

    @Test
    void anInvalidationTheSizeBoundForgotStillStopsAFetchThatStartedOnNoEntry() {
        var small = new EntityCache<String>("competitor", 10, Duration.ofHours(24), LONGEST_FETCH, time, time);
        Stamp onNothing = small.stamp("c1");
        small.invalidate("c1");
        for (int i = 0; i < 10_000 && small.remembersInvalidation("c1"); i++) {
            small.invalidate("other " + i);
        }
        assertThat(small.remembersInvalidation("c1"))
                .as("evicted for room while a fetch could still run")
                .isFalse();
        assertThat(small.forgottenForRoom()).as("and counted").isPositive();
        assertThat(small.writeAuthoritative("c1", profileWrite(EN, "Before The Change"), onNothing))
                .isFalse();
        assertThat(small.writeAuthoritative("c1", profileWrite(EN, "After"), small.stamp("c1")))
                .as("a fetch that started after it writes")
                .isTrue();
    }

    @Test
    void unrelatedInvalidationsTwiceAsManyAsTheEntriesDoNotStopAnotherKeysFetch() {
        var small = new EntityCache<String>("competitor", 10, Duration.ofHours(24), LONGEST_FETCH, time, time);
        Stamp onNothing = small.stamp("c1");
        for (int i = 0; i < 20; i++) {
            small.invalidate("other " + i);
        }
        assertThat(small.forgottenForRoom()).isZero();
        assertThat(small.writeAuthoritative("c1", profileWrite(EN, "Team One"), onNothing))
                .isTrue();
    }

    @Test
    void anInvalidationIsRememberedForTheLongestFetchAndStillStopsAnOlderOne() {
        Stamp onNothing = cache.stamp("c1");
        cache.invalidate("c1");
        time.advance(LONGEST_FETCH.plusSeconds(1));
        assertThat(cache.remembersInvalidation("c1"))
                .as("forgotten after the longest fetch")
                .isFalse();
        assertThat(cache.forgottenForRoom()).as("aged out, not for room").isZero();
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "Before The Change"), onNothing))
                .as("a fetch older than any fetch runs gives way all the same")
                .isFalse();
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "After"), cache.stamp("c1")))
                .isTrue();
    }

    @Test
    void aFetchWhoseInvalidationAgesOutUnderItStillWrites() {
        cache.invalidate("c1");
        time.advance(LONGEST_FETCH.minusSeconds(1));
        Stamp afterIt = cache.stamp("c1");
        time.advance(Duration.ofSeconds(2));
        assertThat(cache.remembersInvalidation("c1")).isFalse();
        assertThat(cache.writeAuthoritative("c1", profileWrite(EN, "Team One"), afterIt))
                .as("nothing happened to the key since the fetch started")
                .isTrue();
    }

    @Test
    void anEntryDroppedAndMadeAgainIsNotTheOneTheFetchStartedWith() {
        profile("c2", EN, "Two", "CZ", "T2", List.of());
        Stamp onEntry = cache.stamp("c2");
        cache.invalidate("c2");
        time.advance(Duration.ofHours(25));
        assertThat(cache.get("c2")).as("the tombstone expired").isNull();
        fill("c2", Write.from(SCHEDULE, EN).put(COUNTRY, "SK"));
        assertThat(cache.writeAuthoritative("c2", profileWrite(EN, "Before The Change"), onEntry))
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
        var matches = new EntityCache<String>("match", 100, Duration.ofHours(12), LONGEST_FETCH, time, time);
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

        var bounded = new EntityCache<String>("competitor", 3, Duration.ofHours(24), LONGEST_FETCH, time, time);
        for (int i = 0; i < 10; i++) {
            bounded.fill("c" + i, Write.from(SCHEDULE, EN).put(NAME, "Team " + i), bounded.stamp("c" + i));
        }
        assertThat(bounded.size()).isEqualTo(3);
    }

    @Test
    void aWriteThatChangesNothingDoesNotKeepAnEntryAlive() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        time.advance(Duration.ofHours(20));
        // a schedule that carries only what the profile wrote adds nothing, and so is no write
        fill("c1", Write.from(SCHEDULE, EN).put(NAME, "Team One"));
        time.advance(Duration.ofHours(5));
        assertThat(cache.get("c1")).as("24 hours after its last change").isNull();
        fill("c9", Write.from(SCHEDULE, EN));
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
    void aSideLoadThatStartedBeforeAnInvalidationFillsNothing() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        Stamp onEntry = cache.stamp("c1");
        Stamp onNothing = cache.stamp("c2");
        cache.invalidate("c1");
        cache.invalidate("c2");
        assertThat(cache.fill("c1", Write.from(SCHEDULE, EN).put(NAME, "Team One"), onEntry))
                .isFalse();
        assertThat(entry("c1").get(NAME, EN)).as("the tombstone stays empty").isNull();
        assertThat(cache.fill("c2", Write.from(SCHEDULE, EN).put(NAME, "Team Two"), onNothing))
                .isFalse();
        assertThat(cache.get("c2")).isNull();
        assertThat(fill("c1", Write.from(SCHEDULE, EN).put(NAME, "Team One"))).isTrue();
        assertThat(entry("c1").get(NAME, EN)).isEqualTo("Team One");
    }

    @Test
    void anAuthoritativeResponseDoesNotBringBackAFieldItsOwnerCleared() {
        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        profile("c1", EN, "Team One", null, "T1", List.of());
        cache.writeAuthoritative(
                "c1", Write.from(ICONS, EN).put(ICON, "icon.png").put(COUNTRY, "SK"), cache.stamp("c1"));
        assertThat(entry("c1").get(COUNTRY, EN)).as("retracted by the profile").isNull();
        assertThat(entry("c1").isAuthoritative(COUNTRY, EN)).isTrue();
        assertThat(entry("c1").get(ICON, null)).isEqualTo("icon.png");
    }

    @Test
    void clearStopsAFetchThatStartedOnNoEntry() {
        Stamp onNothing = cache.stamp("c5");
        cache.clear();
        assertThat(cache.writeAuthoritative("c5", profileWrite(EN, "Before The Clear"), onNothing))
                .isFalse();
        assertThat(cache.writeAuthoritative("c5", profileWrite(EN, "After"), cache.stamp("c5")))
                .isTrue();
    }

    @Test
    void anInvalidationThatRecordsLateDoesNotPutAnOlderGenerationOverANewerOne() throws Exception {
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        cache.insideInvalidate = () -> {
            if (first.getAndSet(false)) {
                inside.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var older = threads.submit(() -> cache.invalidate("c7"));
            assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            cache.invalidate("c7");
            long newer = cache.invalidationOf("c7");
            release.countDown();
            older.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(cache.invalidationOf("c7"))
                    .as("the newer invalidation stays remembered")
                    .isEqualTo(newer)
                    .isEqualTo(cache.stamp("c7").startedAt());
        }
    }

    @Test
    void anInvalidationWaitsForAWriteThatIsMakingTheEntry() throws Exception {
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        cache.insideWrite = () -> {
            inside.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Stamp onNothing = cache.stamp("c8");
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var write = threads.submit(() -> cache.writeAuthoritative("c8", profileWrite(EN, "Before"), onNothing));
            assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var invalidation = threads.submit(() -> cache.invalidate("c8"));
            try {
                Thread.sleep(200);
                assertThat(invalidation.isDone())
                        .as("the invalidation waits for the entry being made")
                        .isFalse();
            } finally {
                release.countDown();
            }
            assertThat(write.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            invalidation.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(entry("c8").get(NAME, EN))
                .as("the write came first, and the invalidation emptied it")
                .isNull();
    }

    @Test
    void clearWaitsForAWriteUnderWaySoItCannotMakeAnEntryAfterTheClear() throws Exception {
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        cache.insideWrite = () -> {
            inside.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Stamp onNothing = cache.stamp("c6");
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var write = threads.submit(() -> cache.writeAuthoritative("c6", profileWrite(EN, "Before"), onNothing));
            assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var clear = threads.submit(cache::clear);
            try {
                Thread.sleep(200);
                assertThat(clear.isDone()).as("the clear waits for the write").isFalse();
            } finally {
                release.countDown();
            }
            assertThat(write.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            clear.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(entry("c6").get(NAME, EN))
                .as("the write came first, and the clear emptied it")
                .isNull();
    }

    @Test
    void anAuthoritativeResponseFillsWhatItCarriesOfFieldsAnotherEndpointOwns() {
        assertThat(cache.writeAuthoritative(
                        "c1", Write.from(ICONS, EN).put(ICON, "icon.png").put(NAME, "Team"), cache.stamp("c1")))
                .isTrue();
        assertThat(entry("c1").get(NAME, EN)).as("filled").isEqualTo("Team");
        assertThat(entry("c1").isAuthoritative(NAME, EN)).isFalse();

        profile("c1", EN, "Team One", "CZ", "T1", List.of());
        cache.writeAuthoritative(
                "c1", Write.from(ICONS, EN).put(ICON, "new.png").put(NAME, "Team"), cache.stamp("c1"));
        assertThat(entry("c1").get(NAME, EN))
                .as("the profile's name is not overwritten")
                .isEqualTo("Team One");
        assertThat(entry("c1").get(ICON, null)).isEqualTo("new.png");
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

    /** A fill from a side-load that started just now. */
    private boolean fill(String key, Write write) {
        return cache.fill(key, write, cache.stamp(key));
    }

    private Stamp stamp(String key) {
        return cache.stamp(key);
    }

    private Entry entry(String key) {
        return requireNonNull(cache.get(key), key);
    }
}
