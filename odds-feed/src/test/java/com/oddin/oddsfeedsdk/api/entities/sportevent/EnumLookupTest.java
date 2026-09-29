package com.oddin.oddsfeedsdk.api.entities.sportevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lookups clients call with REST and feed values. PublicShapeTest pins their signatures; this pins what
 * they answer, taken from the 0.0.x source.
 */
class EnumLookupTest {

    @Test
    void eventStatusesKeepTheirRestNamesAndIds() {
        assertThat(EventStatus.values())
                .extracting(EventStatus::name, EventStatus::getApiName, EventStatus::getApiId)
                .containsExactly(
                        tuple("NotStarted", "not_started", 0),
                        tuple("Live", "live", 1),
                        tuple("Suspended", "suspended", 2),
                        tuple("Ended", "ended", 3),
                        tuple("Finished", "closed", 4),
                        tuple("Cancelled", "cancelled", 5),
                        tuple("Abandoned", "abandoned", 6),
                        tuple("Delayed", "delayed", 7),
                        tuple("Unknown", "unknown", 8),
                        tuple("Postponed", "postponed", 9),
                        tuple("Interrupted", "interrupted", 10));
    }

    @Test
    void anEventStatusIsFoundByItsRestNameAndAnythingElseIsUnknown() {
        for (EventStatus status : EventStatus.values()) {
            assertThat(EventStatus.fromApiEventStatus(status.getApiName()))
                    .as(status.getApiName())
                    .isEqualTo(status);
            assertThat(EventStatus.Companion.fromApiEventStatus(status.getApiName()))
                    .as(status.getApiName())
                    .isEqualTo(status);
        }
        // the constant is Finished, the REST name is closed
        assertThat(EventStatus.fromApiEventStatus("finished")).isEqualTo(EventStatus.Unknown);
        assertThat(EventStatus.fromApiEventStatus("LIVE")).isEqualTo(EventStatus.Unknown);
        assertThat(EventStatus.fromApiEventStatus(null)).isEqualTo(EventStatus.Unknown);
        assertThat(EventStatus.Companion.fromApiEventStatus(null)).isEqualTo(EventStatus.Unknown);
    }

    // 0.0.56 and older mapped only five feed statuses and reported a cancelled match as Unknown.
    // The feed numbers are not getApiId(): the feed's 9 is abandoned, the enum's 6.
    @Test
    void everyFeedStatusNumberIsItsOwnEventStatus() {
        var expected = Map.of(
                0, EventStatus.NotStarted,
                1, EventStatus.Live,
                2, EventStatus.Suspended,
                3, EventStatus.Ended,
                4, EventStatus.Finished,
                5, EventStatus.Cancelled,
                6, EventStatus.Delayed,
                7, EventStatus.Interrupted,
                8, EventStatus.Postponed,
                9, EventStatus.Abandoned);
        for (var entry : expected.entrySet()) {
            var feed = OFEventStatus.fromValue(entry.getKey());
            assertThat(EventStatus.fromFeedEventStatus(feed))
                    .as("feed %d", entry.getKey())
                    .isEqualTo(entry.getValue());
            assertThat(EventStatus.Companion.fromFeedEventStatus(feed))
                    .as("feed %d", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
        // every constant but UNKNOWN has a number above, and UNKNOWN stays Unknown
        assertThat(expected).hasSize(OFEventStatus.values().length - 1);
        assertThat(EventStatus.fromFeedEventStatus(OFEventStatus.UNKNOWN)).isEqualTo(EventStatus.Unknown);
    }

    @Test
    void liveOddsAreAvailableUnlessTheApiSaysOtherwise() {
        assertThat(LiveOddsAvailability.fromApiEvent("not_available")).isEqualTo(LiveOddsAvailability.NOT_AVAILABLE);
        assertThat(LiveOddsAvailability.fromApiEvent("available")).isEqualTo(LiveOddsAvailability.AVAILABLE);
        assertThat(LiveOddsAvailability.fromApiEvent("anything")).isEqualTo(LiveOddsAvailability.AVAILABLE);
        assertThat(LiveOddsAvailability.fromApiEvent(null)).isEqualTo(LiveOddsAvailability.AVAILABLE);
        assertThat(LiveOddsAvailability.Companion.fromApiEvent("not_available"))
                .isEqualTo(LiveOddsAvailability.NOT_AVAILABLE);
        assertThat(LiveOddsAvailability.Companion.fromApiEvent(null)).isEqualTo(LiveOddsAvailability.AVAILABLE);
    }
}
