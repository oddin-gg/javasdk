package com.oddin.oddsfeedsdk.api.entities.sportevent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// Java source written against the releases before clockRunning must keep compiling: the
// 35-argument constructor and the 35-argument copy.
@SuppressWarnings("deprecation")
public class ScoreboardJavaSourceTest {

    @Test
    public void theConstructorAndCopyOfEarlierReleasesCompileFromJavaSource() {
        Scoreboard created = new Scoreboard(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);
        assertNull(created.getClockRunning());

        Scoreboard withFlag = new Scoreboard(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, true);

        Scoreboard copied = withFlag.copy(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(Boolean.TRUE, copied.getClockRunning());
    }
}
