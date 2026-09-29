package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus
import org.junit.Assert.assertEquals
import org.junit.Test

// A feed status the SDK did not map used to reach clients as Unknown: a cancelled
// match read through the SDK said Unknown while the REST API said cancelled.
class EventStatusTest {

    private val expected = mapOf(
        OFEventStatus.NOT_STARTED to EventStatus.NotStarted,
        OFEventStatus.LIVE to EventStatus.Live,
        OFEventStatus.SUSPENDED to EventStatus.Suspended,
        OFEventStatus.ENDED to EventStatus.Ended,
        OFEventStatus.FINALIZED to EventStatus.Finished,
        OFEventStatus.CANCELLED to EventStatus.Cancelled,
        OFEventStatus.DELAYED to EventStatus.Delayed,
        OFEventStatus.INTERRUPTED to EventStatus.Interrupted,
        OFEventStatus.POSTPONED to EventStatus.Postponed,
        OFEventStatus.ABANDONED to EventStatus.Abandoned
    )

    @Test
    fun everyFeedStatusHasItsOwnEventStatus() {
        assertEquals("every feed status needs an expectation", OFEventStatus.values().toSet(), expected.keys)
        expected.forEach { (feed, status) ->
            assertEquals("feed $feed", status, EventStatus.fromFeedEventStatus(feed))
        }
    }
}
