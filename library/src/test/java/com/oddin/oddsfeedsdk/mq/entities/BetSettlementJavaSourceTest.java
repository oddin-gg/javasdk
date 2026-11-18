package com.oddin.oddsfeedsdk.mq.entities;

import static org.junit.Assert.assertEquals;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement;
import org.junit.Test;

// Java source that reads the certainty must keep compiling: with a deprecation warning, not an error.
@SuppressWarnings("deprecation")
public class BetSettlementJavaSourceTest {

    @Test
    public void theCertaintyCanStillBeReadFromJavaSource() {
        BetSettlement<?> message = null;
        if (message != null) {
            BetSettlementCertainty certainty = message.getCertainty();
            assertEquals(BetSettlementCertainty.UNKNOWN, certainty);
        }
        OFBetSettlement raw = new OFBetSettlement();
        raw.setCertainty(1);
        assertEquals(1, raw.getCertainty());
    }
}
